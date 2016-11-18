package cloudpolling;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;

public class DropBoxDownloadProcessor extends CloudDownloadProcessor {

  private static Logger log = Logger.getLogger(DropBoxDownloadProcessor.class);

  public DropBoxDownloadProcessor(PollingProject project) {
    super(project);
  }

  @Override
  public void process(Exchange exchange) throws Exception {

    // Get DropBox client
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    CloudAccount account = new CloudAccount(accountID, getProject());
    account.setConfiguration();
    DbxRequestConfig config = new DbxRequestConfig(account.readConfiguration("configID"));
    DbxClientV2 client = new DbxClientV2(config, account.readConfiguration("accessToken"));

    // Get source file info
    String dropboxPath = exchange.getIn().getHeader("source_path", String.class);
    String details = exchange.getIn().getHeader("details", String.class);

    // Get download destination
    String dest = Paths.get(getProject().getSyncFolder(), "acct" + Integer.toString(accountID), dropboxPath).toString();

    log.info("Downloading a file from DropBox Account " + Integer.toString(accountID) + " to destination: " + dest);

    // Create paths to destination file if they don't exist
    File file = new File(dest);
    if (!file.exists()) {
      File dir = file.getParentFile();
      dir.mkdirs();
      file.createNewFile();
    }

    // Download DropBox File to destination output stream
    FileOutputStream out = new FileOutputStream(file);
    client.files().download(dropboxPath, details).download(out);
    out.flush();
    out.close();
  }

}
