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
   * Connects to Box & starts long polling Box events. On an event, sends
   * exchange to ActionListener & update's account's poll token.
   */
  public void poll() throws Exception {

    final BoxDeveloperEditionAPIConnection api = connect();

    // If stream position is not 0, start an event stream to poll updates on API
    // connection
    if (this.getStreamPosition() != 0) {

      EventStream stream = new EventStream(api, getStreamPosition());

      stream.addListener(new EventListener() {

        JsonObject body;
        HashMap<String, String> headers;

        public void onEvent(BoxEvent event) {
          log.info("Box event received of type: " + event.getType().toString());

          body = event.getSourceJSON();
          headers = new HashMap<String, String>();
          BoxItem.Info srcInfo = (BoxItem.Info) event.getSourceInfo();
          headers.put("source_id", event.getSourceInfo().getID());
          String dest = getFullBoxPath(srcInfo);
          headers.put("destination", dest);

          switch (event.getType()) {

          case ITEM_UPLOAD:
          case EDIT:
          case ITEM_CREATE:
          case ITEM_UNDELETE_VIA_TRASH:
          case UNDELETE:
          case UPLOAD:

            // verify item is a download-able file
            if (!(event.getSourceInfo() instanceof BoxFile.Info)) {
              log.info("Box item created or edited is not a downloadable file - unhandled event.");
            } else {
              headers.put("action", "download");
              sendActionExchangeWithAcctInfo(headers, body.toString());
            }
            break;

          case ITEM_TRASH:
          case DELETE:
            headers.put("action", "delete");

            sendActionExchangeWithAcctInfo(headers, body.toString());
            break;

          default:
            log.info("Unhandled Box event.");
            break;
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

      // If poll token is 0, get a current stream position & download all files
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
    exchangeFolderItems(rootFolder, rootPath);
  }

  /**
   * Sends an exchanges to ActionListener to download all box files under a
   * given folder at a given depth
   *
   * @param folder
   * @param depth
   */
  private void exchangeFolderItems(BoxFolder folder, String path) {

    HashMap<String, String> headers = new HashMap<String, String>();

    for (BoxItem.Info itemInfo : folder) {

      if (itemInfo instanceof BoxFile.Info) {
        BoxFile.Info fileInfo = (BoxFile.Info) itemInfo;

        headers.put("action", "download");
        headers.put("source_id", itemInfo.getID());
        headers.put("destination", Paths.get(path, fileInfo.getName()).toString());
        sendActionExchangeWithAcctInfo(headers, fileInfo.toString());

      } else if (itemInfo instanceof BoxFolder.Info) {
        BoxFolder.Info folderInfo = (BoxFolder.Info) itemInfo;
        exchangeFolderItems(folderInfo.getResource(), Paths.get(path, folderInfo.getName()).toString());
      }
    }
  }

  /**
   * Send message exchange including this account id & type information as
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
