package cloudpolling;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;

/**
 * SyncRouter contains all route configurations to sync disparate Box, DropBox &
 * Drive accounts with a local file system and solr instance
 *
 * @author tlarrue
 *
 */

public class SyncRouter extends RouteBuilder {

  public PollingProject project;
  public ProducerTemplate producer;

  Predicate delete = header("action").isEqualTo("delete");
  Predicate download = header("action").isEqualTo("download");
  Predicate makedir = header("action").isEqualTo("make_directory");
  Predicate box = header("account_type").isEqualTo("box");
  Predicate dropbox = header("account_type").isEqualTo("dropbox");
  Predicate googledrive = header("account_type").isEqualTo("googledrive");

  public SyncRouter(PollingProject project, ProducerTemplate producer) {
    this.project = project;
    this.producer = producer;
  }

  @Override
  public void configure() throws Exception {

    /**
     * Starting Point: poll changes from each cloud source, handle responses by
     * sending exchange to ActionListener route.
     */
    for (int id : this.getProject().getAccountIds()) {

      CloudAccount account = new CloudAccount(id, getProject());
      account.setConfiguration();

      switch (account.getType()) {

      case BOX:
        BoxConnector boxconnect = new BoxConnector(account, this.getProducer());
        from("timer://foo?repeatCount=1").bean(boxconnect, "poll");
        break;

      case DROPBOX:
        DropBoxConnector dbconnect = new DropBoxConnector(account, this.getProducer());
        from("timer://foo?repeatCount=1").bean(dbconnect, "poll");
        break;

      case GOOGLEDRIVE:
        GoogleDriveConnector gdconnect = new GoogleDriveConnector(account, this.getProducer());
        from("timer://foo?repeatCount=1").bean(gdconnect, "poll");
        break;
      }

    }

    /**
     * FolderListener: listens on project's sync folder for changes since last
     * poll & sends file exchange to SolrUpdater
     */
    // NO LONGER NECESSARY
    /*
     * SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss"); Date
     * lastPoll = sdf.parse(this.getProject().readConfiguration("lastPoll"));
     * from("file:" + this.getProject().getSyncFolder() + "?recursive=true" +
     * "&delete=false" + "&noop=true" +
     * "&idempotentKey=${file:name}-${file:modified}")
     * .filter(header("CamelFileLastModified").isGreaterThan(lastPoll))
     * .routeId("FolderListener")
     * .log("Detected a change to local file system.")
     * .to("direct:update.solr");
     */

    /**
     * ActionListener: receives exchanges resulting from polling cloud account
     * changes & redirects them based on the required action specified in
     * 'action' header
     */
    from("direct:actions").streamCaching()
        .routeId("ActionListener")
        .log("Received an event from cloud polling.")
        .choice()
        .when(download)
        .to("direct:download.filesys")
        .when(delete)
        .to("direct:delete.filesys")
        .when(makedir)
        .to("direct:makedir.filesys")
        .otherwise()
        .to("direct:default");

    /**
     * FileDownloader: receives exchanges with info about a file to download &
     * its associated cloud account & processes with appropriate
     * CloudDownloadProcessor determined by 'account_type' header
     */
    from("direct:download.filesys")
        .routeId("FileDownloader")
        .log("Request received to download a file from the cloud.")
        .choice()
        .when(box)
        .process(new BoxDownloadProcessor(this.getProject()))
        .to("direct:update.solr")
        .when(dropbox)
        .process(new DropBoxDownloadProcessor(this.getProject()))
        .to("direct:update.solr")
        .when(googledrive)
        .process(new GoogleDriveDownloadProcessor(this.getProject()))
        .to("direct:update.solr")
        .otherwise()
        .to("direct:default");

    /**
     * FileDeleter: receives message with info about a file to delete & handles
     * by deleting file on local system & sending message to SolrDeleter
     */
    from("direct:delete.filesys")
        .routeId("FileDeleter")
        .log("Deleting a file on local file system")
        .process(new DeleteProcessor(this.getProject()))
        .to("direct:delete.solr");

    /**
     * DirectoryMaker: receives a message with info about a folder to make &
     * handles by making that directory on the local file system.
     */
    from("direct:makedir.filesys")
        .routeId("DirectoryMaker")
        .log("Creating a directory on local file system")
        .process(new MakedirProcessor(this.getProject()))
        .to("direct:update.solr");

    /**
     * SolrUpdater: receives file exchange, processes it, & sends request to
     * solr instance to index update
     */
    // TODO: Add Solr address for updates
    from("direct:update.solr")
        .routeId("SolrUpdater")
        .log("Updating Solr object.");
    // .to("http4://" + this.PROJECT.getSolrURL());

    /**
     * SolrDeleter: receives file exchange, processes it, & sends request to
     * solr instance to delete file from indexing
     */
    // TODO: Add Solr address for deletions
    from("direct:delete.solr")
        .routeId("SolrDeleter")
        .log("Deleting Solr object.");
    // .to("http4://" + this.PROJECT.getSolrURL());

    /**
     * Default Route
     */
    from("direct:default")
        .routeId("DefaultListener")
        .log(LoggingLevel.INFO, "Default Action");

  }

  public PollingProject getProject() {
    return project;
  }

  public ProducerTemplate getProducer() {
    return producer;
  }

}
