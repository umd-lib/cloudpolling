package cloudpolling;

import java.io.File;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.log4j.Logger;

public class MakedirProcessor extends CloudDownloadProcessor {

  PollingProject project;

  private static Logger log = Logger.getLogger(MakedirProcessor.class);

  public MakedirProcessor(PollingProject project) {
    super(project);
  }

  /**
   * Makes a directory in this project's sync folder. If directory's parent
   * folders do not exist, it creates them as well. Then, creates JSON for Solr
   * update.
   */
  @Override
  public void process(Exchange exchange) throws Exception {

    String accountID = exchange.getIn().getHeader("account_id", String.class);
    String sourcePath = exchange.getIn().getHeader("source_path", String.class);
    String destPath = Paths.get(this.getProject().getSyncFolder(), "acct" + accountID, sourcePath).toString();
    File dir = new File(destPath);

    if (!dir.exists()) {
      if (dir.mkdirs()) {
        log.info("Directories created: " + destPath);
      } else {
        log.info("Failed to create directories: " + destPath);
      }
    }

    // create JSON for SolrUpdater exchange
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    log.info("Creating JSON for indexing cloud folder with ID:" + sourceID);
    super.process(exchange);
  }

}
