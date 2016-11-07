package cloudpolling;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.v2.DbxClientV2;

public class DropBoxDownloadProcessor implements Processor {

  PollingProject PROJECT;

  public DropBoxDownloadProcessor(PollingProject project) {
    this.PROJECT = project;
  }

  public void process(Exchange exchange) throws Exception {

    // get dropbox client
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    CloudAccount account = new CloudAccount(accountID, this.PROJECT);
    account.setConfiguration();

    DbxRequestConfig config = new DbxRequestConfig(account.readConfiguration("configID"));
    DbxClientV2 client = new DbxClientV2(config, account.readConfiguration("accessToken"));

    // get source file details
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    String details = exchange.getIn().getBody(String.class);

    // define output file
    String topDest = Paths.get(this.PROJECT.getSyncFolder(), "acct" + Integer.toString(accountID)).toString();
    String thisDest = Paths.get(topDest, sourceID).toString();

    File file = new File(thisDest);

    // create output file if it doesnt exist
    if (!file.exists()) {
      File dir = file.getParentFile();
      dir.mkdirs();
      file.createNewFile();
    }

    FileOutputStream out = new FileOutputStream(file);

    // download source stream to output stream
    client.files().download(sourceID, details).download(out);
    out.flush();
    out.close();
  }

}
