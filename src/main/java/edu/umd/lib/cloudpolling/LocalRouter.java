package edu.umd.lib.cloudpolling;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.PredicateBuilder;
import org.apache.camel.builder.RouteBuilder;

public class LocalRouter extends RouteBuilder {

  /**
   * LocalRouter contains all route configurations to sync Box (DropBox & Drive
   * to be added) folders w/ local file system (local database & solr instance
   * to be added)
   *
   * @author tlarrue
   *
   */

  public PollingProject PROJECT;
  public ProducerTemplate PRODUCER;

  // Predicates for determining nature of requests in MasterQueue
  Predicate download = header("action").isEqualTo("download");
  Predicate box = header("account_type").isEqualTo("box");
  Predicate dropbox = header("account_type").isEqualTo("dropbox");
  Predicate drive = header("account_type").isEqualTo("drive");

  public LocalRouter(PollingProject project, ProducerTemplate producer) {

    this.PRODUCER = producer;
    this.PROJECT = project;
  }

  @Override
  public void configure() throws Exception {

    /**
     * Sends poll requests for each cloud source, parses responses, and sends
     * requests to master queue
     */

    for (int id : this.PROJECT.getAccountIds()) {

      CloudAccount account = new CloudAccount(id, PROJECT.getAccountsDirName());

      switch (account.getType()) {

      case BOX:
        BoxConnector boxconnector = new BoxConnector(account.getConfiguration());
        boxconnector.setPollToken(account.getPollToken());
        boxconnector.setProducer(this.PRODUCER);
        from("timer://foo?period=5000&repeatCount=1").bean(boxconnector, "sendPollRequest");
        break;

      case DROPBOX:
        // TODO: add dropbox
        break;

      case GOOGLEDRIVE:
        // TODO: add google drive
        break;
      }

    }

    /**
     * Route based on request types from master queue.
     */
    from("direct:actions")
        .routeId("ActionListener")
        .log("Received a request from cloud poll processing.")
        .process(new UpdateAccount())
        .choice()
        .when(PredicateBuilder.and(download, box))
        .to("direct:download.box.filesys")
        .otherwise()
        .to("direct:default");

    /**
     * Download a Box file using Box Java SDK
     */
    from("direct:download.box.filesys")
        .routeId("BoxDownloader")
        .log("Downloading a file from a box account.")
        .setHeader("destination", constant(PROJECT.getSyncFolder()))
        .process(new BoxDownloadProcessor());

    /**
     * Handle new Box upload by connecting to box API & downloading file.
     */
    from("direct:download.filesys")
        .routeId("FileDownloader")
        .log("Downloading uploaded cloud file to local file system.")
        .transform().simple("${properties:downloadurl}")
        .to("file:data/outbox"); // TODO: make sure it copies file structure of
                                 // cloud source

    /***
     * Default Route
     */
    from("direct:default")
        .routeId("DefaultListener")
        .log(LoggingLevel.INFO, "Default Action Listener for Listener");

  }

}
