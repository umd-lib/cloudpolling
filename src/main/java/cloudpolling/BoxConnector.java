package cloudpolling;

import java.io.IOException;
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
import com.box.sdk.EncryptionAlgorithm;
import com.box.sdk.EventListener;
import com.box.sdk.EventStream;
import com.box.sdk.IAccessTokenCache;
import com.box.sdk.InMemoryLRUAccessTokenCache;
import com.box.sdk.JWTEncryptionPreferences;
import com.eclipsesource.json.JsonObject;

public class BoxConnector extends CloudConnector {

  private String accountID;
  private String clientID;
  private String clientSecret;
  private String userID;
  private String publicKeyID;
  private String privateKeyFile;
  private String privateKeyPassword;
  private long streamPosition;
  private int maxCacheEntries = 100;

  private static Logger log = Logger.getLogger(BoxConnector.class);

  public BoxConnector(CloudAccount account, ProducerTemplate producer) {
    super(account, producer);
    setAuthInfo(account.getConfiguration());
    setStreamPosition(account.getPollToken());
  }

  public void setAuthInfo(Properties config) {
    setAccountID(config.getProperty("configID"));
    setClientID(config.getProperty("clientID"));
    setClientSecret(config.getProperty("clientSecret"));
    setUserID(config.getProperty("appUserID"));
    setPublicKeyID(config.getProperty("publicKeyID"));
    setPrivateKeyFile(config.getProperty("privateKeyFile"));
    setPrivateKeyPassword(config.getProperty("privateKeyPassword"));
  }

  public void setStreamPosition(String pollToken) {
    if (pollToken != null) {
      streamPosition = Long.parseLong(pollToken);
    } else {
      streamPosition = 0;
    }
  }

  /**
   * Establishes an API connection for a box user
   */
  public BoxDeveloperEditionAPIConnection connect() throws IOException {

    String privateKey = new String(Files.readAllBytes(Paths.get(getPrivateKeyFile())));

    JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
    encryptionPref.setPublicKeyID(getPublicKeyID());
    encryptionPref.setPrivateKey(privateKey);
    encryptionPref.setPrivateKeyPassword(getPrivateKeyPassword());
    encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

    IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(getMaxCacheEntries());

    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppUserConnection(getUserID(),
        getClientID(), getClientSecret(), encryptionPref, accessTokenCache);

    return api;

  }

  /**
   * Sends exchanges to ActionListener to download all files associated with an
   * api connection to account's local sync folder starting from root folder
   *
   * @param api
   */
  public void downloadAllFiles(BoxDeveloperEditionAPIConnection api) {

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
  public void exchangeFolderItems(BoxFolder folder, String path) {

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
   * Returns the full box path of a box item as a String.
   *
   * @param srcInfo
   * @return
   */
  public static String getFullBoxPath(BoxItem.Info srcInfo) {

    BoxItem.Info parentInfo = srcInfo;
    String dest = "";

    while (parentInfo != null) {
      dest = Paths.get(parentInfo.getName(), dest).toString();
      parentInfo = parentInfo.getParent();
    }

    return dest;
  }

  public void startEventStream(EventStream stream) throws Exception {

    // Handle event as received by sending exchange to ActionListener
    stream.addListener(new EventListener() {

      JsonObject body;
      HashMap<String, String> headers = new HashMap<String, String>();

      public void onEvent(BoxEvent event) {

        log.info("Box event received of type: " + event.getType().toString());

        body = event.getSourceJSON();
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
        setStreamPosition(position);
      }

      public boolean onException(Throwable e) {
        e.printStackTrace();
        return false;
      }
    });

    log.info("Starting event long polling for Box Account " + accountID + "...");
    stream.start();
    Thread.sleep(1000 * 30 * 1); // 30 seconds to receive events from box
    getAccount().updateConfiguration("pollToken", Long.toString(getStreamPosition()));
    stream.stop();

  }

  public void sendActionExchangeWithAcctInfo(HashMap<String, String> headers, String body) {
    headers.put("account_id", getAccountID());
    headers.put("account_type", "box");
    sendActionExchange(headers, body);
  }

  /**
   * Connects to Box & starts long polling Box events. On an event, sends
   * exchange to ActionListener & update's account's poll token.
   */
  public void poll() throws Exception {

    final BoxDeveloperEditionAPIConnection api = connect();

    if (getStreamPosition() != 0) {

      startEventStream(new EventStream(api, getStreamPosition()));

    } else {

      String urlString = String.format(api.getBaseURL() + "events?stream_position=%s", "now");
      URL url = new URL(urlString);
      BoxAPIRequest request = new BoxAPIRequest(api, url, "GET");
      BoxJSONResponse response = (BoxJSONResponse) request.send();
      JsonObject jsonObject = JsonObject.readFrom(response.getJSON());
      Long position = jsonObject.get("next_stream_position").asLong();

      setStreamPosition(position);
      getAccount().updateConfiguration("pollToken", Long.toString(getStreamPosition()));

      log.info("First time connecting to Box Account " + accountID
          + ". Downloading all account items to local sync folder...");
      downloadAllFiles(api);
    }
  }

  private String getAccountID() {
    return accountID;
  }

  public String getClientID() {
    return clientID;
  }

  public void setClientID(String clientID) {
    this.clientID = clientID;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getUserID() {
    return userID;
  }

  public void setUserID(String userID) {
    this.userID = userID;
  }

  public String getPublicKeyID() {
    return publicKeyID;
  }

  public void setPublicKeyID(String publicKeyID) {
    this.publicKeyID = publicKeyID;
  }

  public String getPrivateKeyFile() {
    return privateKeyFile;
  }

  public void setPrivateKeyFile(String privateKeyFile) {
    this.privateKeyFile = privateKeyFile;
  }

  public String getPrivateKeyPassword() {
    return privateKeyPassword;
  }

  public void setPrivateKeyPassword(String privateKeyPassword) {
    this.privateKeyPassword = privateKeyPassword;
  }

  public long getStreamPosition() {
    return streamPosition;
  }

  public void setStreamPosition(long streamPosition) {
    this.streamPosition = streamPosition;
  }

  public void setAccountID(String accountID) {
    this.accountID = accountID;
  }

  public int getMaxCacheEntries() {
    return maxCacheEntries;
  }

  public void setMaxCacheEntries(int maxCacheEntries) {
    this.maxCacheEntries = maxCacheEntries;
  }

}
