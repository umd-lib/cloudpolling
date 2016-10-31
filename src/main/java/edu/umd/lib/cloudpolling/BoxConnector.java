package edu.umd.lib.cloudpolling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

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
  /**
   * This class uses Box Java SDK to establish a connection and interact with a
   * Box CloudAccount. Holds account authentication data & API connection
   * object.
   */

  private static String ACCOUNT_ID;
  private static String CLIENT_ID;
  private static String CLIENT_SECRET;
  private static String USER_ID;
  private static String PUBLIC_KEY_ID;
  private static String PRIVATE_KEY_FILE;
  private static String PRIVATE_KEY_PASSWORD;
  private static int MAX_CACHE_ENTRIES;
  public static long STREAM_POSITION;

  private static ProducerTemplate PRODUCER;

  public BoxConnector(Properties config) {

    ACCOUNT_ID = config.getProperty("configID");
    CLIENT_ID = config.getProperty("clientID");
    CLIENT_SECRET = config.getProperty("clientSecret");
    USER_ID = config.getProperty("appUserID");
    PUBLIC_KEY_ID = config.getProperty("publicKeyID");
    PRIVATE_KEY_FILE = config.getProperty("privateKeyFile");
    PRIVATE_KEY_PASSWORD = config.getProperty("privateKeyPassword");
    STREAM_POSITION = 0;
    MAX_CACHE_ENTRIES = 100;

  }

  public void setProducer(ProducerTemplate p) {
    PRODUCER = p;
  }

  public void setPollToken(String token) {
    if (token != null) {
      STREAM_POSITION = Integer.parseInt(token);
    }
  }

  public BoxDeveloperEditionAPIConnection connect() throws IOException {
    /**
     * Establish & authenticate an API connection
     */

    String privateKey = new String(Files.readAllBytes(Paths.get(PRIVATE_KEY_FILE)));

    JWTEncryptionPreferences encryptionPref = new JWTEncryptionPreferences();
    encryptionPref.setPublicKeyID(PUBLIC_KEY_ID);
    encryptionPref.setPrivateKey(privateKey);
    encryptionPref.setPrivateKeyPassword(PRIVATE_KEY_PASSWORD);
    encryptionPref.setEncryptionAlgorithm(EncryptionAlgorithm.RSA_SHA_256);

    // It is a best practice to use an access token cache to prevent unneeded
    // requests to Box for access tokens.
    // For production applications it is recommended to use a distributed cache
    // like Memcached or Redis, and to
    // implement IAccessTokenCache to store and retrieve access tokens
    // appropriately for your environment.
    IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(MAX_CACHE_ENTRIES);

    BoxDeveloperEditionAPIConnection api = BoxDeveloperEditionAPIConnection.getAppUserConnection(USER_ID, CLIENT_ID,
        CLIENT_SECRET, encryptionPref, accessTokenCache);

    return api;

  }

  public void sendPollRequest() throws IOException {
    /**
     * Connects & starts long polling Box events. On an event, sends exchange to
     * master queue to handle event and updates BoxConnector's STREAM_POSITION.
     */

    BoxDeveloperEditionAPIConnection api = connect();

    // Turn off logging to prevent polluting the output.
    Logger.getLogger("edu.umd.lib").setLevel(Level.OFF);

    // Construct an event stream from the last stream position
    EventStream stream;
    if (STREAM_POSITION != 0) {
      stream = new EventStream(api, STREAM_POSITION);
    } else {
      stream = new EventStream(api);
    }

    // Handle events by sending requests [request type specified in header; raw
    // event info provided in body] to MasterQueue
    stream.addListener(new EventListener() {
      public void onEvent(BoxEvent event) {

        String sourceID = event.getSourceInfo().getID();
        JsonObject json = event.getSourceJSON();
        List<String> requests = new ArrayList<String>();

        switch (event.getType()) {

        case ITEM_UPLOAD:
          requests.add("download");
          break;

        default:
          System.out.println("Event received of type: " + event.getType().toString());
          break;
        }

        for (String request : requests) {
          sendRequestToQueue(request, sourceID, json.toString());
        }

      }

      public void onNextPosition(long position) {
        STREAM_POSITION = position;
      }

      public boolean onException(Throwable e) {
        e.printStackTrace();
        return false;
      }
    });
    stream.start();

  }

  public static void sendRequestToQueue(String action, String sourceID, String body) {
    /**
     * Sends out a new exchange to Master Queue
     */

    Exchange exchange = new DefaultExchange(PRODUCER.getCamelContext());
    Message message = new DefaultMessage();

    message.setBody(body);
    message.setHeader("source_id", sourceID);
    message.setHeader("account_id", ACCOUNT_ID);
    message.setHeader("account_type", "box");
    message.setHeader("action", action);

    exchange.setIn(message);

    PRODUCER.send("direct:actions", exchange);

  }

}
