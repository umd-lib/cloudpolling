package cloudpolling2;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxFile;

public class BoxDownloadProcessor implements Processor {

  PollingProject PROJECT;

  public BoxDownloadProcessor(PollingProject project) {
    this.PROJECT = project;
  }

  public void process(Exchange exchange) throws Exception {

    // Define BoxConnector object
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    CloudAccount account = new CloudAccount(accountID, this.PROJECT);
    account.setConfiguration();
    BoxConnector connector = new BoxConnector(account.getConfiguration());

    // Connect to Box & get file to download
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    BoxAPIConnection api = connector.connect();
    BoxFile srcFile = new BoxFile(api, sourceID);
    String srcFileName = srcFile.getInfo().getName();

    // Download to destination location
    // TODO: recursively find file structure to place file in local structure
    // correctly
    String topDest = this.PROJECT.getSyncFolder();
    String thisDest = Paths.get(topDest, srcFileName).toString();

    File file = new File(thisDest);
    FileOutputStream out = new FileOutputStream(file);

    if (!file.exists()) {
      file.createNewFile();
    }

    srcFile.download(out);
    out.flush();
    out.close();

  }

}
