package cloudpolling;

import java.io.File;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.log4j.Logger;

public class DeleteProcessor implements Processor {

  PollingProject project;

  private static Logger log = Logger.getLogger(DeleteProcessor.class);

  public DeleteProcessor(PollingProject project) {
    setProject(project);
  }

  public void process(Exchange exchange) throws Exception {

    // Delete file from local file system
    String accountID = exchange.getIn().getHeader("account_id", String.class);
    String destFile = exchange.getIn().getHeader("destination", String.class);
    String fileName = Paths.get(project.getSyncFolder(), "acct" + accountID, destFile).toString();
    File file = new File(fileName);
    boolean fileDeleted = file.delete();
    if (fileDeleted) {
      log.info("File deleted: " + fileName);
    } else {
      log.info("WARNING: Could NOT delete file: " + fileName);
    }

    // create JSON for Solr Exchange
    String file_ID = exchange.getIn().getHeader("source_id", String.class);
    String delete_json = "{ \"delete\" : { \"id\" : \"" + file_ID + "\" } }";
    exchange.getIn().setBody(delete_json);
    log.info("Creating JSON for deleting cloud file with ID:" + file_ID);

  }

  public PollingProject getProject() {
    return this.project;
  }

  public void setProject(PollingProject p) {
    this.project = p;
  }

}
