package cloudpolling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.camel.ProducerTemplate;
import org.apache.log4j.Logger;

import com.dropbox.core.DbxApiException;
import com.dropbox.core.DbxAppInfo;
import com.dropbox.core.DbxAuthFinish;
import com.dropbox.core.DbxAuthInfo;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxHost;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.NetworkIOException;
import com.dropbox.core.http.StandardHttpRequestor;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.DeletedMetadata;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.ListFolderLongpollResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.ListRevisionsErrorException;
import com.dropbox.core.v2.files.ListRevisionsResult;
import com.dropbox.core.v2.files.Metadata;

/**
 * Represents a connection between a DropBox account and a camel context.
 *
 * @author tlarrue
 *
 */
public class DropBoxConnector extends CloudConnector {

  private String accountID;
  private String appKey;
  private String appSecret;
  private String userID;
  private String accessToken;
  private String pollFolder;
  private String cursor;

  private static Logger log = Logger.getLogger(DropBoxConnector.class);

  /**
   * Constructs a DropBoxConnector from a cloud account and producer template.
   * This constructor also provides instructions to authorize application to
   * folder if there is no access token in the account's configuration file.
   *
   * @param account
   * @param producer
   */
  public DropBoxConnector(CloudAccount account, ProducerTemplate producer) {
    super(account, producer);
    setAuthInfo(this.getAccount().getConfiguration());

    // authorize application if there is no access token
    DbxRequestConfig requestConfig = new DbxRequestConfig(this.getAccountID());
    if (this.getAccessToken().toUpperCase().contains("FILL")) {
      try {
        authorizeApp(requestConfig);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Sets authentication fields for this DropBox connection from this account's
   * configuration.
   *
   * @param config
   */
  private void setAuthInfo(Properties config) {
    this.accountID = config.getProperty("configID");
    this.appKey = config.getProperty("appKey");
    this.appSecret = config.getProperty("appSecret");
    this.userID = config.getProperty("userID");
    this.accessToken = config.getProperty("accessToken");
    this.pollFolder = config.getProperty("pollFolder", "");
    this.cursor = config.getProperty("pollToken");
  }

  /**
   * Connects to DropBox and starts long polling Box events. On an event, sends
   * exchange to ActionListener and update's account's poll token.
   *
   * @throws IOException
   */
  public void poll() throws IOException {

    // Create 2 DropBox clients:
    // 1) for long poll request (longer read timeout)
    // 2) for all other requests
    long longpollTimeoutSecs = TimeUnit.MINUTES.toSeconds(1);
    DbxAuthInfo auth = new DbxAuthInfo(this.getAccessToken(), DbxHost.DEFAULT);
    StandardHttpRequestor.Config config = StandardHttpRequestor.Config.DEFAULT_INSTANCE;
    StandardHttpRequestor.Config longpollConfig = config.copy()
        // read timeout should be greater than our longpoll timeout and include
        // enough buffer for the jitter introduced by the server. The server
        // will add a random amount of delay to our longpoll timeout to avoid
        // the stampeding herd problem. See DbxFiles.listFolderLongpoll(String,
        // long) documentation for details.
        .withReadTimeout(5, TimeUnit.MINUTES)
        .build();
    DbxClientV2 dbxClient = createClient(getAccountID(), auth, config);
    DbxClientV2 dbxLongpollClient = createClient(getAccountID(), auth, longpollConfig);
    ListFolderResult result = null;
    boolean ignoreDeleted = false;

    try {

      if (this.getCursor().length() == 1) {
        // If cursor=="0", request all items in DropBox account
        log.info("First time connecting to DropBox Account " + accountID
            + ". Downloading all account items to local sync folder...");

        result = dbxClient.files()
            .listFolderBuilder(this.getPollFolder())
            .withIncludeDeleted(true)
            .withIncludeMediaInfo(false)
            .withRecursive(true)
            .start();
        ignoreDeleted = true;

      } else {
        // If cursor !="0", only request changes since last cursor
        log.info("Longpolling for DropBox changes... press CTRL-C to exit.");

        ListFolderLongpollResult longpollResult = dbxLongpollClient.files()
            .listFolderLongpoll(this.getCursor(), longpollTimeoutSecs);

        if (longpollResult.getChanges()) {
          result = dbxClient.files().listFolderContinue(this.getCursor());
        }
      }

      // process all entries in request results
      while (true) {
        for (Metadata metadata : result.getEntries()) {
          processItem(metadata, dbxClient, ignoreDeleted);
        }
        if (!result.getHasMore()) {
          break;
        }
        result = dbxClient.files().listFolderContinue(result.getCursor());
      }

      // update this cursor & poll token in account configuration
      this.cursor = result.getCursor();
      this.getAccount().updateConfiguration("pollToken", this.getCursor());

    } catch (DbxApiException ex) {
      // if a user message is available, try using that instead
      String message = ex.getUserMessage() != null ? ex.getUserMessage().getText() : ex.getMessage();
      System.err.println("Error making API call: " + message);
      System.exit(1);
    } catch (NetworkIOException ex) {
      System.err.println("Error making API call: " + ex.getMessage());
      if (ex.getCause() instanceof SocketTimeoutException) {
        System.err.println("Consider increasing socket read timeout or decreasing longpoll timeout.");
      }
      System.exit(1);
    } catch (DbxException ex) {
      System.err.println("Error making API call: " + ex.getMessage());
      System.exit(1);
    }

  }

  /**
   * Processes a DropBox item (file, folder, or deleted item) by sending message
   * exchange to ActionListener with instructions to sync local file system Sync
   * Processing described here:
   * https://www.dropbox.com/developers/documentation/http/documentation#files-list_folder
   *
   * @param metadata
   * @throws DbxException
   * @throws ListRevisionsErrorException
   */
  private void processItem(Metadata metadata, DbxClientV2 client, boolean ignoreDeleted)
      throws ListRevisionsErrorException, DbxException {

    HashMap<String, String> headers = new HashMap<String, String>();

    if (metadata instanceof FileMetadata) {
      FileMetadata fileMetadata = (FileMetadata) metadata;
      headers.put("action", "download");
      headers.put("source_id", fileMetadata.getId());
      headers.put("source_name", fileMetadata.getName());
      headers.put("source_path", fileMetadata.getPathLower());
      Path parentPath = Paths.get(fileMetadata.getPathLower()).getParent();
      String parentID;
      try {
        FolderMetadata parentMetadata = (FolderMetadata) client.files().getMetadata(parentPath.toString());
        parentID = parentMetadata.getId();
      } catch (DbxException ex) {
        parentID = "0"; // dummy ID for root folder
      }
      headers.put("parent_id", parentID);
      headers.put("details", fileMetadata.getRev()); // revision id
      headers.put("source_type", "file");
      headers.put("metadata", "none"); // TODO: gather custom metadata from
                                       // FileMetadata attributes

    } else if (metadata instanceof FolderMetadata) {
      FolderMetadata folderMetadata = (FolderMetadata) metadata;
      headers.put("action", "make_directory");
      headers.put("source_id", folderMetadata.getId());
      headers.put("source_name", folderMetadata.getName());
      headers.put("source_path", folderMetadata.getPathLower());
      Path parentPath = Paths.get(folderMetadata.getPathLower()).getParent();
      String parentID;
      try {
        FolderMetadata parentMetadata = (FolderMetadata) client.files().getMetadata(parentPath.toString());
        parentID = parentMetadata.getId();
      } catch (DbxException ex) {
        parentID = "0"; // dummy ID for root folder
      }
      headers.put("details", parentID);
      headers.put("source_type", "folder");
      headers.put("metadata", "none"); // TODO: gather custom metadata from
                                       // FolderMetadata attributes

    } else if (metadata instanceof DeletedMetadata) {

      if (!ignoreDeleted) {
        // find id of deleted item
        ListRevisionsResult result = client.files().listRevisions(metadata.getPathLower());
        FileMetadata fm = result.getEntries().get(0);
        String deleted_id = fm.getId();

        if (deleted_id != null) {
          headers.put("source_id", deleted_id);
        } else {
          headers.put("source_id", "unknown");
        }

        headers.put("action", "delete");
        headers.put("source_path", metadata.getPathLower());
        headers.put("details", "remove_childen");
      }

    } else {
      throw new IllegalStateException("Unrecognized metadata type: " + metadata.getClass());
    }

    headers.put("account_type", "dropbox");
    headers.put("account_id", this.getAccountID());
    sendActionExchange(headers, "");
  }

  /**
   * Create a new Dropbox client using the given authentication information and
   * HTTP client config.
   *
   * @param auth
   *          Authentication information
   * @param config
   *          HTTP request configuration
   *
   * @return new Dropbox V2 client
   */
  private static DbxClientV2 createClient(String accountID, DbxAuthInfo auth, StandardHttpRequestor.Config config) {
    String clientUserAgentId = accountID;
    StandardHttpRequestor requestor = new StandardHttpRequestor(config);
    DbxRequestConfig requestConfig = DbxRequestConfig.newBuilder(clientUserAgentId)
        .withHttpRequestor(requestor)
        .build();

    return new DbxClientV2(requestConfig, auth.getAccessToken(), auth.getHost());
  }

  /**
   * Run Dropbox API Authorization process
   *
   * @param requestConfig
   * @throws IOException
   */
  private void authorizeApp(DbxRequestConfig requestConfig) throws IOException {
    DbxAppInfo appInfo = new DbxAppInfo(this.getAppKey(), this.getAppSecret());
    DbxWebAuth webAuth = new DbxWebAuth(requestConfig, appInfo);
    DbxWebAuth.Request webAuthRequest = DbxWebAuth.newRequestBuilder()
        .withNoRedirect()
        .build();

    String authorizeUrl = webAuth.authorize(webAuthRequest);
    System.out.println("1. Go to " + authorizeUrl);
    System.out.println("2. Click \"Allow\" (you might have to log in first).");
    System.out.println("3. Copy the authorization code.");
    System.out.print("Enter the authorization code here: ");

    String code = new BufferedReader(new InputStreamReader(System.in)).readLine();
    if (code == null) {
      System.exit(1);
      return;
    }
    code = code.trim();

    DbxAuthFinish authFinish;
    try {
      authFinish = webAuth.finishFromCode(code);
    } catch (DbxException ex) {
      System.err.println("Error in DbxWebAuth.authorize: " + ex.getMessage());
      System.exit(1);
      return;
    }

    System.out.println("Authorization complete.");
    System.out.println("- User ID: " + authFinish.getUserId());
    System.out.println("- Access Token: " + authFinish.getAccessToken());

    // save authorization info
    this.userID = authFinish.getUserId();
    this.getAccount().updateConfiguration("userID", this.getUserID());
    this.accessToken = authFinish.getAccessToken();
    this.getAccount().updateConfiguration("accessToken", this.getAccessToken());
  }

  public String getAccountID() {
    return accountID;
  }

  public String getAppKey() {
    return appKey;
  }

  public String getAppSecret() {
    return appSecret;
  }

  public String getUserID() {
    return userID;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public String getCursor() {
    return cursor;
  }

  public String getPollFolder() {
    return pollFolder;
  }

}
