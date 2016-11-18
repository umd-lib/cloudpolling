package cloudpolling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
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
import com.dropbox.core.v2.files.ListFolderErrorException;
import com.dropbox.core.v2.files.ListFolderGetLatestCursorResult;
import com.dropbox.core.v2.files.ListFolderLongpollResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;

public class DropBoxConnector extends CloudConnector {

  private String accountID;
  private String appKey;
  private String appSecret;
  private String userID;
  private String accessToken;
  private String cursor;

  private static Logger log = Logger.getLogger(DropBoxConnector.class);

  public DropBoxConnector(CloudAccount account, ProducerTemplate producer) {
    super(account, producer);
    setAuthInfo(this.getAccount().getConfiguration());
    setCursor(this.getAccount().getPollToken());

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

  public void setAuthInfo(Properties config) {
    setAccountID(config.getProperty("configID"));
    setAppKey(config.getProperty("appKey"));
    setAppSecret(config.getProperty("appSecret"));
    setUserID(config.getProperty("userID"));
    setAccessToken(config.getProperty("accessToken"));
  }

  public void downloadAllFiles(DbxClientV2 client) throws ListFolderErrorException, DbxException {
    ListFolderResult result = client.files().listFolder("");

    while (true) {
      for (Metadata metadata : result.getEntries()) {
        constructHeadersAndSend(metadata);
      }

      if (!result.getHasMore()) {
        break;
      }

      result = client.files().listFolderContinue(result.getCursor());
    }
  }

  public void poll() throws IOException {

    long longpollTimeoutSecs = TimeUnit.MINUTES.toSeconds(1);

    // Create 2 Dropbox clients:
    // (1) One for longpoll requests, with its read timeout set longer than our
    // polling timeout
    // (2) One for all other requests, with its read timeout set to the default,
    // shorter timeout

    DbxAuthInfo auth = new DbxAuthInfo(this.getAccessToken(), DbxHost.DEFAULT);
    StandardHttpRequestor.Config config = StandardHttpRequestor.Config.DEFAULT_INSTANCE;
    StandardHttpRequestor.Config longpollConfig = config.copy()
        // read timeout should be greater than our longpoll timeout and include
        // enough buffer
        // for the jitter introduced by the server. The server will add a random
        // amount of delay
        // to our longpoll timeout to avoid the stampeding herd problem. See
        // DbxFiles.listFolderLongpoll(String, long) documentation for details.
        .withReadTimeout(5, TimeUnit.MINUTES)
        .build();

    DbxClientV2 dbxClient = createClient(getAccountID(), auth, config);
    DbxClientV2 dbxLongpollClient = createClient(getAccountID(), auth, longpollConfig);

    try {
      // We only care about file changes, not existing files, so grab latest
      // cursor for this
      // path and then longpoll for changes.

      if (getCursor().length() == 1) {
        log.info("First time connecting to DropBox Account " + accountID
            + ". Downloading all account items to local sync folder...");
        downloadAllFiles(dbxClient);
        setCursor(getLatestCursor(dbxClient, ""));
        getAccount().updateConfiguration("pollToken", getCursor());
      } else {
        log.info("Longpolling for DropBox changes... press CTRL-C to exit.");

        // will block for longpollTimeoutSecs or until a change is made in the
        // folder
        ListFolderLongpollResult result = dbxLongpollClient.files()
            .listFolderLongpoll(getCursor(), longpollTimeoutSecs);

        // parse the responses into exchanges & update poll token for the
        // account
        if (result.getChanges()) {
          parseChanges(dbxClient, getCursor());
        }
      }

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

  private void constructHeadersAndSend(Metadata metadata) {

    HashMap<String, String> headers = new HashMap<String, String>();

    if (metadata instanceof FileMetadata) {
      FileMetadata fileMetadata = (FileMetadata) metadata;
      headers.put("action", "download");
      headers.put("source_id", fileMetadata.getId());
      headers.put("source_type", "file");
      headers.put("source_path", fileMetadata.getPathLower());
      headers.put("details", fileMetadata.getRev());

    } else if (metadata instanceof FolderMetadata) {
      FolderMetadata folderMetadata = (FolderMetadata) metadata;
      headers.put("action", "download");
      headers.put("source_id", folderMetadata.getId());
      headers.put("source_type", "folder");
      headers.put("source_path", folderMetadata.getPathLower());
      headers.put("details", folderMetadata.getSharingInfo().toString());

    } else if (metadata instanceof DeletedMetadata) {
      headers.put("source_id", "UNKNOWN");
      headers.put("action", "delete");
      headers.put("source_type", "deleted");
      headers.put("destination", metadata.getPathLower());
      headers.put("details", "");

    } else {
      throw new IllegalStateException("Unrecognized metadata type: " + metadata.getClass());
    }

    headers.put("account_type", "dropbox");
    headers.put("account_id", getAccountID());
    sendActionExchange(headers, "");
  }

  private void parseChanges(DbxClientV2 client, String cursor) throws DbxApiException, DbxException {
    while (true) {

      ListFolderResult result = client.files().listFolderContinue(cursor);

      for (Metadata metadata : result.getEntries()) {
        constructHeadersAndSend(metadata);
      }

      // update cursor to fetch remaining results
      setCursor(result.getCursor());
      getAccount().updateConfiguration("pollToken", getCursor());

      if (!result.getHasMore()) {
        break;
      }
    }
  }

  /**
   * Returns latest cursor for listing changes to a directory in Dropbox with
   * the given path.
   *
   * @param dbxClient
   *          Dropbox client to use for fetching the latest cursor
   * @param path
   *          path to directory in Dropbox
   *
   * @return cursor for listing changes to the given Dropbox directory
   */
  private static String getLatestCursor(DbxClientV2 dbxClient, String path)
      throws DbxApiException, DbxException {
    ListFolderGetLatestCursorResult result = dbxClient.files()
        .listFolderGetLatestCursorBuilder(path)
        .withIncludeDeleted(true)
        .withIncludeMediaInfo(false)
        .withRecursive(true)
        .start();
    return result.getCursor();
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

  public void authorizeApp(DbxRequestConfig requestConfig) throws IOException {
    /**
     * Run Dropbox API Authorization process
     */

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
    this.setUserID(authFinish.getUserId());
    this.getAccount().updateConfiguration("userID", this.getUserID());
    this.setAccessToken(authFinish.getAccessToken());
    this.getAccount().updateConfiguration("accessToken", this.getAccessToken());
  }

  public String getAccountID() {
    return accountID;
  }

  public void setAccountID(String accountID) {
    this.accountID = accountID;
  }

  public String getAppKey() {
    return appKey;
  }

  public void setAppKey(String appKey) {
    this.appKey = appKey;
  }

  public String getAppSecret() {
    return appSecret;
  }

  public void setAppSecret(String appSecret) {
    this.appSecret = appSecret;
  }

  public String getUserID() {
    return userID;
  }

  public void setUserID(String userID) {
    this.userID = userID;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public String getCursor() {
    return cursor;
  }

  public void setCursor(String cursor) {
    this.cursor = cursor;
  }

}
