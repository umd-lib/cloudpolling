package cloudpolling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;

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
import com.dropbox.core.v2.files.ListFolderGetLatestCursorResult;
import com.dropbox.core.v2.files.ListFolderLongpollResult;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;

public class DropBoxConnector {

  private CloudAccount ACCOUNT;

  private String ACCOUNT_ID;
  private String APP_KEY;
  private String APP_SECRET;
  private String USER_ID;
  private String ACCESS_TOKEN;

  private String CURSOR;

  private ProducerTemplate PRODUCER;

  public DropBoxConnector(CloudAccount account, ProducerTemplate producer) {
    /**
     * Initializes DropBox connection configuration
     */

    this.ACCOUNT = account;
    setAuthInfo(account.getConfiguration());
    this.CURSOR = account.readConfiguration("pollToken");
    setProducer(producer);

    DbxRequestConfig requestConfig = new DbxRequestConfig(this.ACCOUNT_ID);

    // authorize application if there is no access token
    if (this.ACCESS_TOKEN.toUpperCase().contains("FILL")) {
      try {
        authorizeApp(requestConfig);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void setProducer(ProducerTemplate producer) {
    this.PRODUCER = producer;
  }

  public void setAuthInfo(Properties config) {
    this.ACCOUNT_ID = config.getProperty("configID");
    this.APP_KEY = config.getProperty("appKey");
    this.APP_SECRET = config.getProperty("appSecret");
    this.USER_ID = config.getProperty("userID");
    this.ACCESS_TOKEN = config.getProperty("accessToken");
  }

  public void sendPollRequest() throws IOException {

    long longpollTimeoutSecs = TimeUnit.MINUTES.toSeconds(2);

    // Create 2 Dropbox clients:
    // (1) One for longpoll requests, with its read timeout set longer than our
    // polling timeout
    // (2) One for all other requests, with its read timeout set to the default,
    // shorter timeout

    DbxAuthInfo auth = new DbxAuthInfo(this.ACCESS_TOKEN, DbxHost.DEFAULT);
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

    DbxClientV2 dbxClient = createClient(this.ACCOUNT_ID, auth, config);
    DbxClientV2 dbxLongpollClient = createClient(this.ACCOUNT_ID, auth, longpollConfig);

    try {
      // We only care about file changes, not existing files, so grab latest
      // cursor for this
      // path and then longpoll for changes.
      if (this.CURSOR == "0") {
        this.CURSOR = getLatestCursor(dbxClient, "");
        this.ACCOUNT.updateConfiguration("pollToken", this.CURSOR);
      }

      System.out.println("Longpolling for changes... press CTRL-C to exit.");

      // will block for longpollTimeoutSecs or until a change is made in the
      // folder
      ListFolderLongpollResult result = dbxLongpollClient.files()
          .listFolderLongpoll(this.CURSOR, longpollTimeoutSecs);

      // we have changes, list them
      if (result.getChanges()) {
        this.CURSOR = parseChanges(dbxClient, this.CURSOR);
        this.ACCOUNT.updateConfiguration("pollToken", this.CURSOR);
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

  private String parseChanges(DbxClientV2 client, String cursor) throws DbxApiException, DbxException {
    while (true) {
      ListFolderResult result = client.files()
          .listFolderContinue(cursor);
      for (Metadata metadata : result.getEntries()) {
        String action; // download or delete
        String source_id; // source path for dropbox
        String details; // current revision of the file or sharing info
        String type; // file, folder, or deleted
        if (metadata instanceof FileMetadata) {
          FileMetadata fileMetadata = (FileMetadata) metadata;
          action = "download";
          source_id = fileMetadata.getPathLower();
          details = fileMetadata.getRev();
          type = "file";

        } else if (metadata instanceof FolderMetadata) {
          FolderMetadata folderMetadata = (FolderMetadata) metadata;
          type = "folder";
          action = "download";
          source_id = folderMetadata.getPathLower();
          details = folderMetadata.getSharingInfo().toString();

        } else if (metadata instanceof DeletedMetadata) {
          type = "deleted";
          action = "delete";
          source_id = metadata.getPathLower();
          details = "";

        } else {
          throw new IllegalStateException("Unrecognized metadata type: " + metadata.getClass());
        }

        sendRequestToQueue(type, action, source_id, details);
      }
      // update cursor to fetch remaining results
      // cursor = result.getCursor();

      if (!result.getHasMore()) {
        break;
      }
    }

    return cursor;
  }

  private void sendRequestToQueue(String sourceType, String action, String sourceID, String details) {
    /**
     * Sends out a new exchange to ActionListener
     */

    Exchange exchange = new DefaultExchange(this.PRODUCER.getCamelContext());
    Message message = new DefaultMessage();

    message.setBody(details);
    message.setHeader("source_id", sourceID);
    message.setHeader("source_type", sourceType);
    message.setHeader("account_id", this.ACCOUNT_ID);
    message.setHeader("account_type", "dropbox");
    message.setHeader("action", action);

    exchange.setIn(message);

    this.PRODUCER.send("direct:actions", exchange);

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

    DbxAppInfo appInfo = new DbxAppInfo(this.APP_KEY, this.APP_SECRET);
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
    this.USER_ID = authFinish.getUserId();
    this.ACCOUNT.updateConfiguration("userID", this.USER_ID);
    this.ACCESS_TOKEN = authFinish.getAccessToken();
    this.ACCOUNT.updateConfiguration("accessToken", this.ACCESS_TOKEN);
  }

}
