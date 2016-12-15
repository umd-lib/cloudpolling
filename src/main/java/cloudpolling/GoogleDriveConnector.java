package cloudpolling;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import org.apache.camel.ProducerTemplate;
import org.apache.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.Change;
import com.google.api.services.drive.model.ChangeList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Revision;
import com.google.api.services.drive.model.RevisionList;
import com.google.api.services.drive.model.StartPageToken;

/**
 * Represents a connection between a Google Drive account and a camel context.
 *
 * @author tlarrue
 *
 */
public class GoogleDriveConnector extends CloudConnector {

  private final String accountID;
  private final String appName;
  private final String clientSecretFileName;
  private final java.io.File dataStoreDir;
  private FileDataStoreFactory dataStoreFactory;
  private String pageToken;

  private static Logger log = Logger.getLogger(GoogleDriveConnector.class);

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
  private static HttpTransport HTTP_TRANSPORT;
  private static final List<String> SCOPES = Arrays.asList(DriveScopes.DRIVE);

  static {
    try {
      HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Constructs a google drive connector from a cloud account and a producer
   * template.
   *
   * @param account
   * @param producer
   */
  public GoogleDriveConnector(CloudAccount account, ProducerTemplate producer) {
    super(account, producer);
    Properties config = this.getAccount().getConfiguration();
    this.accountID = config.getProperty("configID");
    this.appName = config.getProperty("appName");
    this.clientSecretFileName = config.getProperty("clientSecretFile");
    String accountConfigDir = this.getAccount().getConfigFile().getParent();
    this.dataStoreDir = new java.io.File(accountConfigDir, ".credentials/googledrive_" + this.accountID);
    try {
      this.dataStoreFactory = new FileDataStoreFactory(this.dataStoreDir);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(1);
    }
    this.pageToken = config.getProperty("pollToken", "0");
  }

  /**
   * Gets the absolute path of a file or folder as it stands in cloud storage.
   *
   * @param service
   * @param item
   * @return
   * @throws IOException
   */
  private String getSourcePath(Drive service, File item) throws IOException {

    String fullPath = null;
    String itemName = item.getName();
    List<String> parentIDs = item.getParents();

    StringBuffer fullPathStringBuffer = new StringBuffer("");

    if (parentIDs == null) {
      fullPath = itemName;
    } else {
      for (String id : parentIDs) {
        File parent = service.files().get(id).execute();
        String parentName = parent.getName();
        fullPathStringBuffer.append("/").append(parentName);
      }
    }

    fullPathStringBuffer.append("/").append(itemName);
    fullPath = fullPathStringBuffer.toString();

    return fullPath;
  }

  /**
   * Connects to a Google Drive account and starts syncing a local system by
   * detecting changes to account since last interval and sending message
   * exchange requests
   *
   * @throws IOException
   * @throws JSONException
   */
  public void poll() throws IOException, JSONException {

    String token = this.pageToken;
    Drive service = getDriveService(); // authorized API client service

    // If it is the first time connecting, download all files from this account.
    // If it is NOT the first time connecting, begin polling changes from the
    // last saved polling token
    if (token.equals("0")) {

      downloadAllFiles(service);

      // save latest page token
      StartPageToken response = service.changes().getStartPageToken().execute();
      this.pageToken = response.getStartPageToken();
      this.getAccount().updateConfiguration("pollToken", this.pageToken);

    } else {

      while (token != null) {

        ChangeList changes = service.changes().list(token).execute();

        for (Change change : changes.getChanges()) {

          File changeItem = change.getFile();
          log.info("Change detected for Google Drive Account " + this.accountID + " item: "
              + change.getFileId());

          if (change.getRemoved()) {
            sendDeleteRequest(service, change);

          } else if (changeItem.getMimeType().equals("application/vnd.google-apps.folder")) {

            sendMakedirRequest(service, changeItem);

          } else {

            sendDownloadRequest(service, changeItem);

          }
        }

        // save latest page token
        if (changes.getNewStartPageToken() != null) {
          this.pageToken = changes.getNewStartPageToken();
          this.getAccount().updateConfiguration("pollToken", this.pageToken);
        }

        token = changes.getNextPageToken();

      }

    }

  }

  /**
   * Sends requests to make all directories and download all files associated
   * with a Google Drive account.
   *
   * @param service
   * @throws IOException
   * @throws JSONException
   */
  private void downloadAllFiles(Drive service) throws IOException, JSONException {

    log.info("First time connecting to Google Drive Account " + this.accountID + ".");
    log.info("Sending requests to download all files of this account...");

    FileList result = service.files().list()
        .setPageSize(10) // 10 item limit - CHANGE AFTER TESTING
        .setFields("nextPageToken, files")
        .execute();

    List<File> files = result.getFiles();

    for (File file : files) {

      if (file.getMimeType().equals("application/vnd.google-apps.folder")) {

        sendMakedirRequest(service, file);

      } else {

        sendDownloadRequest(service, file);

      }

    }

  }

  /**
   * Sends a new message exchange to ActionListener requesting to delete a file
   * or folder from the local system, along with its children.
   *
   * @param service
   * @param file
   * @throws IOException
   */
  private void sendDeleteRequest(Drive service, Change change) throws IOException {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "delete");
    headers.put("source_id", change.getFileId());
    headers.put("details", "remove_childen");

    // get revisions of deleted file
    RevisionList revList = service.revisions()
        .list(change.getFileId())
        .execute();

    List<Revision> revisions = revList.getRevisions();
    String prevRevID = revisions.get(0).getId();

    File deletedFile = service.files()
        .get(change.getFileId())
        .set("revisionId", prevRevID)
        .execute();

    // file source path of the deleted file
    headers.put("source_path", getSourcePath(service, deletedFile));

    if (deletedFile.getMimeType().equals("application/vnd.google-apps.folder")) {
      headers.put("source_type", "folder");
    } else {
      headers.put("source_type", "file");
    }
    headers.put("account_type", "googledrive");
    headers.put("account_id", this.accountID);
    sendActionExchange(headers, "");
  }

  /**
   * Sends a new message exchange to ActionListener requesting to make a
   * directory on the local system.
   *
   * @param service
   * @param file
   * @throws IOException
   */
  private void sendMakedirRequest(Drive service, File file) throws IOException {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "make_directory");
    headers.put("source_type", "folder");
    headers.put("source_id", file.getId());
    headers.put("source_path", getSourcePath(service, file));
    headers.put("source_name", file.getName());
    List<String> parentIDs = file.getParents();
    if (parentIDs == null) {
      headers.put("parent_id", "");
    } else {
      headers.put("parent_id", parentIDs.get(0));
    }
    headers.put("account_type", "googledrive");
    headers.put("account_id", this.accountID);
    sendActionExchange(headers, "");
  }

  /**
   * Send a new message exchange to ActionListener requesting to download a file
   * to the local system.
   *
   * @param service
   * @param file
   * @throws IOException
   * @throws JSONException
   */
  private void sendDownloadRequest(Drive service, File file) throws IOException, JSONException {

    HashMap<String, String> headers = new HashMap<String, String>();

    headers.put("action", "download");
    headers.put("source_type", "file");
    headers.put("source_id", file.getId());
    headers.put("source_path", getSourcePath(service, file));
    headers.put("source_name", file.getName());
    List<String> parentIDs = file.getParents();
    if (parentIDs == null) {
      headers.put("parent_id", "");
    } else {
      headers.put("parent_id", parentIDs.get(0));
    }

    JSONObject meta = new JSONObject();
    meta.put("description", file.getDescription());
    headers.put("metadata", meta.toString());

    headers.put("account_type", "googledrive");
    headers.put("account_id", this.accountID);

    sendActionExchange(headers, "");
  }

  /**
   * Returns credential object for an authorized connection to a google drive
   * account
   *
   * @return
   * @throws IOException
   */
  public Credential authorize() throws IOException {
    // Load client secrets.
    InputStream in = new FileInputStream(this.clientSecretFileName);
    GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

    // Build flow and trigger user authorization request.
    // TODO: modify builder so user and API developer can be treated separately
    GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
            .setDataStoreFactory(this.dataStoreFactory)
            .setAccessType("offline")
            .build();
    Credential credential = new AuthorizationCodeInstalledApp(
        flow, new LocalServerReceiver()).authorize("acct" + this.accountID);
    log.info(
        "Credentials saved to " + this.dataStoreDir.getAbsolutePath());

    return credential;
  }

  /**
   * Build and return an authorized Drive client service.
   *
   * @return an authorized Drive client service
   * @throws IOException
   */
  public Drive getDriveService() throws IOException {
    Credential credential = authorize();
    return new Drive.Builder(
        HTTP_TRANSPORT, JSON_FACTORY, credential)
            .setApplicationName(this.appName)
            .build();
  }

}
