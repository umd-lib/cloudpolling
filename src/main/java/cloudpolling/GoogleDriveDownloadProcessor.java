package cloudpolling;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.log4j.Logger;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

public class GoogleDriveDownloadProcessor extends CloudDownloadProcessor {

  private static Logger log = Logger.getLogger(GoogleDriveDownloadProcessor.class);

  public GoogleDriveDownloadProcessor(PollingProject project) {
    super(project);
  }

  @Override
  public void process(Exchange exchange) throws Exception {
    // To download a file from Google Drive, you need an authorized drive client
    // service and a file ID

    // Get authorized Drive client service
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    CloudAccount account = new CloudAccount(accountID, getProject());
    account.setConfiguration();
    ProducerTemplate dummy = new DefaultCamelContext().createProducerTemplate();
    GoogleDriveConnector connector = new GoogleDriveConnector(account, dummy);
    Drive service = connector.getDriveService();

    // Get file & get its file type
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    File file = service.files().get(sourceID).execute();
    String sourceMimeType = file.getMimeType();
    String downloadMimeType = null;

    // Choose a download type
    // All options listed here:
    // https://developers.google.com/drive/v3/web/manage-downloads
    if (sourceMimeType.equals("application/vnd.google-apps.document") || sourceMimeType.equals("application/pdf")) {
      // Download google documents as PDFs
      downloadMimeType = "application/pdf";
    } else if (sourceMimeType.equals("application/vnd.google-apps.spreadsheet")) {
      // Download google spreadsheets as MS Excel files
      downloadMimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    } else if (sourceMimeType.equals("application/vnd.google-apps.drawing")) {
      // Download google drawings as jpegs
      downloadMimeType = "image/jpeg";
    } else if (sourceMimeType.equals("application/vnd.google-apps.presentation")) {
      // Download google slides as MS powerpoint
      downloadMimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    } else if (sourceMimeType.equals("application/vnd.google-apps.script")) {
      // Download google apps scripts as JSON
      downloadMimeType = "application/vnd.google-apps.script+json";
    }

    if (downloadMimeType != null) {

      // Get download destination
      String sourcePath = exchange.getIn().getHeader("source_path", String.class);
      String syncFolder = getProject().getSyncFolder();
      String acct = "acct" + Integer.toString(accountID);
      String dest = Paths.get(syncFolder, acct, sourcePath).toString();

      // Create paths to destination file if they don't exist
      java.io.File outputFile = new java.io.File(dest);
      if (!outputFile.exists()) {
        java.io.File dir = outputFile.getParentFile();
        dir.mkdirs();
        outputFile.createNewFile();
      }

      // Export file to output stream
      OutputStream out = new FileOutputStream(outputFile);
      service.files().export(sourceID, downloadMimeType).executeMediaAndDownloadTo(out);
      out.flush();
      out.close();

      // create JSON for SolrUpdater exchange
      log.info("Creating JSON for indexing Google Drive file with ID:" + sourceID);
      super.process(exchange);

    } else {
      log.info("Cannot download google file of type: " + sourceMimeType);
      // service.files().get(sourceID).executeMediaAndDownloadTo(out);
    }

  }

}
