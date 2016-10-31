package edu.umd.lib.cloudpolling;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Paths;
import java.util.HashMap;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxFile;

public class BoxDownloadProcessor implements Processor {

  /**
   * Connects to box using a given API & downloads to a destination
   *
   */

  private static HashMap<Integer, CloudAccount> allAccounts;
  private static CloudAccount thisAccount;

  public BoxDownloadProcessor(HashMap<Integer, CloudAccount> accounts) {
    allAccounts = accounts;
  }

  public void process(Exchange exchange) throws Exception {

    // get BoxConnector object
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    thisAccount = allAccounts.get(accountID);
    allAccounts = null;

    // connect & get file to download from Box
    BoxConnector boxConnector = new BoxConnector(thisAccount.getConfiguration());
    BoxAPIConnection api = boxConnector.connect();
    BoxFile boxSrcFile = new BoxFile(api, sourceID);
    String boxSrcFilename = boxSrcFile.getInfo().getName();

    // download to destination location
    // TODO: recursively find file structure to place file in local structure
    // correctly
    String topDest = exchange.getIn().getHeader("destination", String.class);
    String thisDest = Paths.get(topDest, boxSrcFilename).toString();

    File file = new File(thisDest);
    FileOutputStream out = new FileOutputStream(file);

    if (!file.exists()) {
      file.createNewFile();
    }

    boxSrcFile.download(out);
    out.flush();
    out.close();

  }

}
