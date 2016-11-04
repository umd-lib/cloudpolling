package cloudpolling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Properties;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;

import com.box.sdk.BoxDeveloperEditionAPIConnection;
import com.box.sdk.BoxEvent;
import com.box.sdk.EncryptionAlgorithm;
import com.box.sdk.EventListener;
import com.box.sdk.EventStream;
import com.box.sdk.IAccessTokenCache;
import com.box.sdk.InMemoryLRUAccessTokenCache;
import com.box.sdk.JWTEncryptionPreferences;
import com.eclipsesource.json.JsonObject;

public class BoxConnector {

  private String ACCOUNT_ID;
  private String CLIENT_ID;
  private String CLIENT_SECRET;
  private String USER_ID;
  private String PUBLIC_KEY_ID;
  private String PRIVATE_KEY_FILE;
  private String PRIVATE_KEY_PASSWORD;
  private int MAX_CACHE_ENTRIES;

  private long STREAM_POSITION;

  private ProducerTemplate PRODUCER;

  public BoxConnector(Properties config, String pollToken, ProducerTemplate producer) {
    /**
     * Initializes Box connection configuration
     */

    setAuthInfo(config);
    setStreamPosition(pollToken);
    setProducer(producer);

  }

  public BoxConnector(Properties config) {
    setAuthInfo(config);
  }

  public void setAuthInfo(Properties config) {
    this.ACCOUNT_ID = config.getProperty("configID");
    this.CLIENT_ID = config.getProperty("clientID");
    this.CLIENT_SECRET = config.getProperty("clientSecret");
    this.USER_ID = config.getProperty("appUserID");
    this.PUBLIC_KEY_ID = config.getProperty("publicKeyID");
    this.PRIVATE_KEY_FILE = config.getProperty("privateKeyFile");
    this.PRIVATE_KEY_PASSWORD = config.getProperty("privateKeyPassword");
    this.MAX_CACHE_ENTRIES = 100;
  }

  public void setStreamPosition(String pollToken) {
    if (pollToken != null) {
      this.STREAM_POSITION = Long.parseLong(pollToken);
    } else {
      this.STREAM_POSITION = 0;
    }
  }

  public void setProducer(ProducerTemplate producer) {
    this.PRODUCER = producer;
  }

  public BoxDeveloperEditionAPIConnection connect() throws IOException {
    /**
     * Establishes an API connection
     */

    String privateKey = new String(Files.readAllBytes(Paths.get(this.PRIVATE_KEY_FILE)));

    JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
    encryptionPref.setPublicKeyID(this.PUBLIC_KEY_ID);
    encryptionPref.setPrivateKey(privateKey);
    encryptionPref.setPrivateKeyPassword(this.PRIVATE_KEY_PASSWORD);
    encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

    // It is a best practice to use an access token cache to prevent unneeded
    // requests to Box for access tokens.
    // For production applications it is recommended to use a distributed cache
    // like Memcached or Redis, and to
    // implement IAccessTokenCache to store and retrieve access tokens
    // appropriately for your environment.
    IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(this.MAX_CACHE_ENTRIES);

    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppUserConnection(this.USER_ID,
        this.CLIENT_ID,
        this.CLIENT_SECRET, encryptionPref, accessTokenCache);

    return api;

  }

  public void sendPollRequest() throws Exception {
    /**
     * Connects to Box & starts long polling Box events. On an event, sends
     * exchange to ActionListener.
     */

    BoxDeveloperEditionAPIConnection api = connect();

    // Construct an event stream from the last stream position
    EventStream stream;
    if (this.STREAM_POSITION != 0) {
      stream = new EventStream(api, this.STREAM_POSITION);
    } else {
      stream = new EventStream(api);
    }

    // Handle events as they are received by sending messages to ActionListener
    stream.addListener(new EventListener() {

      String sourceID;
      JsonObject json;
      ArrayList<String> actions;

      public void onEvent(BoxEvent event) {

        sourceID = event.getSourceInfo().getID();
        json = event.getSourceJSON();
        actions = new ArrayList<String>();

        System.out.println("Box event received of type: " + event.getType().toString());

        // TODO: Add more event types
        switch (event.getType()) {

        case ITEM_UPLOAD:
          actions.add("download");
          break;

        default:
          break;
        }

        for (String action : actions) {
          sendRequestToQueue(action, sourceID, json.toString());
        }

      }

      public void onNextPosition(long position) {
        STREAM_POSITION = position;
        System.out.println("Box position update received");
        sendRequestToQueue("update_token", Long.toString(STREAM_POSITION), json.toString());
      }

      public boolean onException(Throwable e) {
        e.printStackTrace();
        return false;
      }
    });

    stream.start();
    Thread.sleep(1000); // 1 second to receive events from box
    stream.stop();

  }

  public void sendRequestToQueue(String action, String sourceID, String body) {
    /**
     * Sends out a new exchange to ActionListener
     */

    Exchange exchange = new DefaultExchange(this.PRODUCER.getCamelContext());
    Message message = new DefaultMessage();

    message.setBody(body);
    message.setHeader("source_id", sourceID);
    message.setHeader("account_id", this.ACCOUNT_ID);
    message.setHeader("account_type", "box");
    message.setHeader("action", action);

    exchange.setIn(message);

    this.PRODUCER.send("direct:actions", exchange);

  }

}
