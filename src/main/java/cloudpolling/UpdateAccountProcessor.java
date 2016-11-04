package cloudpolling;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class UpdateAccountProcessor implements Processor {

  PollingProject PROJECT;

  public UpdateAccountProcessor(PollingProject project) {
    this.PROJECT = project;
  }

  public void process(Exchange exchange) throws Exception {

    // Define CloudAccount object
    int accountID = exchange.getIn().getHeader("account_id", Integer.class);
    CloudAccount account = new CloudAccount(accountID, this.PROJECT);
    account.setConfiguration();

    // Update poll token
    String pollToken = exchange.getIn().getHeader("source_id", String.class);
    account.updateConfiguration("pollToken", pollToken);

  }

}
