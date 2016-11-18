package cloudpolling;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.log4j.Logger;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxFile;

public class BoxDownloadProcessor extends CloudDownloadProcessor {

  private static Logger log = Logger.getLogger(BoxDownloadProcessor.class);

  public BoxDownloadProcessor(PollingProject project) {
    super(project);
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    // get API connection
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    CloudAccount account = new CloudAccount(accountID, getProject());
    account.setConfiguration();
    ProducerTemplate dummy = new DefaultCamelContext().createProducerTemplate();
    BoxConnector connector = new BoxConnector(account, dummy);
    BoxAPIConnection api = connector.connect();

    // Connect to Box & get file to download
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    BoxFile srcFile = new BoxFile(api, sourceID);

    // Get download destination
    String boxPath = exchange.getIn().getHeader("destination", String.class);
    String syncFolder = getProject().getSyncFolder();
    String acct = "acct" + Integer.toString(accountID);
    String dest = Paths.get(syncFolder, acct, boxPath).toString();

    log.info("Downloading a file from Box Account " + Integer.toString(accountID) + " to destination: " + dest);

    // Create paths to destination file if they don't exist
    File file = new File(dest);
    if (!file.exists()) {
      File dir = file.getParentFile();
      dir.mkdirs();
      file.createNewFile();
    }

    // Download Box File to destination output stream
    FileOutputStream out = new FileOutputStream(file);
    srcFile.download(out);
    out.flush();
    out.close();

  }

}
