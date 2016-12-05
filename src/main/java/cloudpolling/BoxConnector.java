package cloudpolling;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

import org.apache.camel.ProducerTemplate;
import org.apache.log4j.Logger;

import com.box.sdk.BoxAPIRequest;
import com.box.sdk.BoxDeveloperEditionAPIConnection;
import com.box.sdk.BoxEvent;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxJSONResponse;
import com.box.sdk.BoxUser;
import com.box.sdk.CreateUserParams;
import com.box.sdk.EncryptionAlgorithm;
import com.box.sdk.EventListener;
import com.box.sdk.EventStream;
import com.box.sdk.IAccessTokenCache;
import com.box.sdk.InMemoryLRUAccessTokenCache;
import com.box.sdk.JWTEncryptionPreferences;
import com.eclipsesource.json.JsonObject;

/**
 * Represents a connection between a box account and a camel context.
 *
 * @author tlarrue
 *
 */
public class BoxConnector extends CloudConnector {

  private String accountID;
  private String clientID;
  private String clientSecret;
  private String appUserName;
  private String enterpriseID;
  private String userID;
  private String publicKeyID;
  private String privateKeyFile;
  private String privateKeyPassword;
  private long streamPosition;
  private int maxCacheEntries = 100;

  private static Logger log = Logger.getLogger(BoxConnector.class);

  /**
   * Constructs a box connector from a cloud account and a producer template
   *
   * @param account
   * @param producer
   */
  public BoxConnector(CloudAccount account, ProducerTemplate producer) {
    super(account, producer);
    setAuthInfo(account.getConfiguration());
  }

  /**
   * Sets authentication fields for this box connection from this account's
   * configuration.
   *
   * @param config
   */
  public void setAuthInfo(Properties config) {
    this.accountID = config.getProperty("configID");
    this.clientID = config.getProperty("clientID");
    this.enterpriseID = config.getProperty("enterpriseID");
    this.clientSecret = config.getProperty("clientSecret");
    this.userID = config.getProperty("appUserID");
    this.appUserName = config.getProperty("appUserName");
    this.publicKeyID = config.getProperty("publicKeyID");
    this.privateKeyFile = config.getProperty("privateKeyFile");
    this.privateKeyPassword = config.getProperty("privateKeyPassword");
    this.streamPosition = defineStreamPosition(config.getProperty("pollToken"));
  }

  /**
   * Establishes an API connection for a box app user
   *
   * @return a box API connection
   * @throws IOException
   */
  public BoxDeveloperEditionAPIConnection connect() throws IOException {

    String privateKey = new String(Files.readAllBytes(Paths.get(this.getPrivateKeyFile())));

    JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
    encryptionPref.setPublicKeyID(this.getPublicKeyID());
    encryptionPref.setPrivateKey(privateKey);
    encryptionPref.setPrivateKeyPassword(this.getPrivateKeyPassword());
    encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

    IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(getMaxCacheEntries());

    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppUserConnection(this.getUserID(),
        this.getClientID(), this.getClientSecret(), encryptionPref, accessTokenCache);

    return api;
  }

  /**
   * Connects to Box and starts long polling Box events. On an event, sends
   * exchange to ActionListener and update's account's poll token.
   */
  public void poll() throws Exception {

    final BoxDeveloperEditionAPIConnection api = connect();

    // If stream position is not 0, start an event stream to poll updates on API
    // connection
    if (this.getStreamPosition() != 0) {

      EventStream stream = new EventStream(api, getStreamPosition());

      stream.addListener(new EventListener() {

        String body;
        HashMap<String, String> headers;

        public void onEvent(BoxEvent event) {

          log.info("Box event received of type: " + event.getType().toString());

          body = event.toString();
          headers = new HashMap<String, String>();

          BoxItem.Info srcInfo = (BoxItem.Info) event.getSourceInfo();
          if (srcInfo != null) {

            headers.put("source_id", srcInfo.getID());
            headers.put("source_name", srcInfo.getName());
            headers.put("source_path", getFullBoxPath(srcInfo));

            switch (event.getType()) {

            case ITEM_UPLOAD:
            case ITEM_CREATE:
            case ITEM_UNDELETE_VIA_TRASH:
            case ITEM_COPY:

              if (srcInfo instanceof BoxFile.Info) {

                // BoxFile file = (BoxFile) srcInfo.getResource();
                BoxFile file = new BoxFile(api, srcInfo.getID());

                headers.put("source_type", "file");
                BoxFolder.Info parent = file.getInfo().getParent();
                headers.put("parent_id", parent.getID());
                try {
                  headers.put("metadata", file.getMetadata().toString());
                } catch (Exception ex) {
                  headers.put("metadata", "none");
                }

                headers.put("action", "download");

              } else {
                BoxFolder folder = new BoxFolder(api, srcInfo.getID());
                headers.put("source_type", "folder");
                BoxFolder.Info parent = folder.getInfo().getParent();
                headers.put("parent_id", parent.getID());
                headers.put("action", "make_directory");
              }
              sendActionExchangeWithAcctInfo(headers, body);
              break;

            case ITEM_RENAME:
            case ITEM_MOVE:

              // It does not look like there is a way to see attributes of a
              // previous version of file.
              // If an item is renamed or moved, we download the most recent
              // version of the file
              // BUT THE OLD VERSION MAY STILL BE IN THE LOCAL STORE since we
              // cannot look it up by path location
              // NOTE: We can 'guess' the location of the old file, if it is
              // still in
              // the same directory using BoxFile.getVersions(), and
              // BoxFileVersion.getName(), but I do not implement this here
              // since it will only work some of the time.
              // I leave these 2 event types to handled separately in case a new
              // handling method is found.

              if (srcInfo instanceof BoxFile.Info) {

                // BoxFile file = (BoxFile) srcInfo.getResource();
                BoxFile file = new BoxFile(api, srcInfo.getID());

                headers.put("source_type", "file");
                BoxFolder.Info parent = file.getInfo().getParent();
                headers.put("parent_id", parent.getID());
                try {
                  headers.put("metadata", file.getMetadata().toString());
                } catch (Exception ex) {
                  headers.put("metadata", "none");
                }

                headers.put("action", "download");

              } else {
                BoxFolder folder = new BoxFolder(api, srcInfo.getID());
                headers.put("source_type", "folder");
                BoxFolder.Info parent = folder.getInfo().getParent();
                headers.put("parent_id", parent.getID());
                headers.put("action", "make_directory");
              }
              sendActionExchangeWithAcctInfo(headers, body);
              break;

            case ITEM_TRASH:
              headers.put("action", "delete");
              headers.put("details", "remove_childen");
              sendActionExchangeWithAcctInfo(headers, body);
              break;

            default:
              log.info("Unhandled Box event.");
              break;
            }

          }

        }

        public void onNextPosition(long position) {
          updatePollToken(position);
        }

        public boolean onException(Throwable e) {
          e.printStackTrace();
          return false;
        }
      });

      log.info("Starting event long polling for Box Account " + accountID + "...");

      stream.start();
      Thread.sleep(1000 * 30 * 1); // 30 seconds to receive events from box
      stream.stop();

      // If poll token is 0, get a current stream position and download all
      // files
      // from that account to the sync folder
    } else {
      updatePollToken(getCurrentStreamPosition(api));
      log.info("First time connecting to Box Account " + this.getAccountID()
          + ". Downloading all account items to local sync folder...");
      downloadAllFiles(api);
    }
  }

  /**
   * Creates a new app user for the enterprise of this cloud account.
   *
   * @return user id of the enterprise admin. Use this to establish an API
   *         connection as an app user.
   * @throws IOException
   */
  public String createAppUser() throws IOException {

    String privateKey = new String(Files.readAllBytes(Paths.get(this.getPrivateKeyFile())));

    JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
    encryptionPref.setPublicKeyID(this.getPublicKeyID());
    encryptionPref.setPrivateKey(privateKey);
    encryptionPref.setPrivateKeyPassword(this.getPrivateKeyPassword());
    encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

    IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(this.getMaxCacheEntries());

    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppEnterpriseConnection(
        this.getEnterpriseID(), this.getClientID(), this.getClientSecret(), encryptionPref, accessTokenCache);

    CreateUserParams params = new CreateUserParams();
    params.setSpaceAmount(1073741824); // 1 GB
    BoxUser.Info user = BoxUser.createAppUser(api, this.getAppUserName(), params);

    System.out.format("User created with name %s and id %s\n\n", this.getAppUserName(), user.getID());

    return user.getID();
  }

  /**
   * Sends exchanges to ActionListener to download all files associated with an
   * api connection to account's local sync folder starting from root folder
   *
   * @param api
   */
  private void downloadAllFiles(BoxDeveloperEditionAPIConnection api) {
    BoxFolder rootFolder = BoxFolder.getRootFolder(api);
    String rootPath = Paths.get("").toString();
    exchangeFolderItems(rootFolder, rootPath, api);
  }

  /**
   * Sends an exchanges to ActionListener to download all box files under a
   * given folder at a given depth
   *
   * @param folder
   * @param depth
   */
  private void exchangeFolderItems(BoxFolder folder, String path, BoxDeveloperEditionAPIConnection api) {

    for (BoxItem.Info itemInfo : folder) {

      HashMap<String, String> headers = new HashMap<String, String>();
      String itemID = itemInfo.getID();
      String itemName = itemInfo.getName();
      String itemPath = Paths.get(path, itemName).toString();
      headers.put("source_id", itemID);
      headers.put("source_name", itemName);
      headers.put("source_path", itemPath);

      if (itemInfo instanceof BoxFile.Info) {
        BoxFile file = new BoxFile(api, itemID); // need to connect again to get
                                                 // all file info

        headers.put("action", "download");
        headers.put("parent_id", file.getInfo().getParent().getID());
        sendActionExchangeWithAcctInfo(headers, file.getInfo().toString());

      } else if (itemInfo instanceof BoxFolder.Info) {
        BoxFolder dir = new BoxFolder(api, itemID); // need to connect again to
                                                    // get all folder info

        headers.put("action", "make_directory");
        BoxFolder.Info parent = dir.getInfo().getParent();
        if (parent == null) {
          headers.put("parnet_id", "none");
        } else {
          headers.put("parent_id", parent.getID());
        }

        sendActionExchangeWithAcctInfo(headers, dir.getInfo().toString());
        exchangeFolderItems(dir, itemPath, api);
      }
    }
  }

  /**
   * Send message exchange including this account id and type information as
   * headers.
   *
   * @param headers
   * @param body
   */
  private void sendActionExchangeWithAcctInfo(HashMap<String, String> headers, String body) {
    headers.put("account_id", this.getAccountID());
    headers.put("account_type", "box");
    sendActionExchange(headers, body);
  }

  /**
   * Defines a box stream position (as long) from a poll token string.
   *
   * @param pollToken
   * @return box stream position
   */
  public long defineStreamPosition(String pollToken) {
    long position;
    if (pollToken != null && pollToken != "0") {
      position = Long.parseLong(pollToken);
    } else {
      position = 0;
    }
    return position;
  }

  /**
   * Updates this account's poll token from a box stream position
   *
   * @param position
   */
  private void updatePollToken(long position) {
    this.streamPosition = position;
    this.getAccount().updateConfiguration("pollToken", Long.toString(getStreamPosition()));
  }

  /**
   * Returns the full box path of a box item as a String.
   *
   * @param srcInfo
   * @return absolute path name of box item in box file system
   */
  private static String getFullBoxPath(BoxItem.Info srcInfo) {

    BoxItem.Info parentInfo = srcInfo;
    String dest = "";

    while (parentInfo != null) {
      dest = Paths.get(parentInfo.getName(), dest).toString();
      parentInfo = parentInfo.getParent();
    }
    return dest;
  }

  /**
   * Gets a Box stream position for given api connection for the current
   * date/time
   *
   * @param api
   * @return
   * @throws MalformedURLException
   */
  private static long getCurrentStreamPosition(BoxDeveloperEditionAPIConnection api) throws MalformedURLException {

    String urlString = String.format(api.getBaseURL() + "events?stream_position=%s", "now");
    URL url = new URL(urlString);
    BoxAPIRequest request = new BoxAPIRequest(api, url, "GET");
    BoxJSONResponse response = (BoxJSONResponse) request.send();
    JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
    Long position = jsonObject.get("next_stream_position").asLong();

    return position;
  }

  private String getAccountID() {
    return accountID;
  }

  private String getClientID() {
    return clientID;
  }

  private String getClientSecret() {
    return clientSecret;
  }

  private String getUserID() {
    return userID;
  }

  private String getPublicKeyID() {
    return publicKeyID;
  }

  private String getPrivateKeyFile() {
    return privateKeyFile;
  }

  private String getPrivateKeyPassword() {
    return privateKeyPassword;
  }

  private long getStreamPosition() {
    return streamPosition;
  }

  private int getMaxCacheEntries() {
    return maxCacheEntries;
  }

  private String getAppUserName() {
    return appUserName;
  }

  private String getEnterpriseID() {
    return enterpriseID;
  }

}
