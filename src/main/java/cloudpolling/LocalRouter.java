package cloudpolling;

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
  Predicate updateToken = header("action").isEqualTo("update_token");
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
     * Sends 1 poll request to each cloud source & handles responses by sending
     * "action" messages to 'direct:actions' queue.
     */

    for (int id : this.PROJECT.getAccountIds()) {

      CloudAccount account = new CloudAccount(id, PROJECT);
      account.setConfiguration();

      switch (account.getType()) {

      case BOX:
        BoxConnector boxconnector = new BoxConnector(account.getConfiguration(), account.getPollToken(), this.PRODUCER);
        from("timer://foo?repeatCount=1").bean(boxconnector, "sendPollRequest");
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
    from("direct:actions").streamCaching()
        .routeId("ActionListener")
        .log("Received a request from cloud poll processing.")
        .choice()
        .when(updateToken)
        .to("direct:update.token")
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
        .process(new BoxDownloadProcessor(PROJECT));

    /**
     * Update Poll Token of a CloudAccount
     */
    from("direct:update.token")
        .routeId("TokenUpdater")
        .log("Updating CloudAccount poll token")
        .process(new UpdateAccountProcessor(PROJECT));

    /***
     * Default Route
     */
    from("direct:default")
        .routeId("DefaultListener")
        .log(LoggingLevel.INFO, "Default Action Listener for Listener");

  }

}
