package cloudpolling;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;

/**
 * The abstract base class for connections made by all cloud account types.
 *
 * This class handles sending message exchanges with information received from
 * polling updates from cloud accounts.
 *
 * @author tlarrue
 *
 */
public abstract class CloudConnector {

  private CloudAccount account;
  private ProducerTemplate producer;

  /**
   * Constructs a cloud connector from a cloud account and producer template
   *
   * @param account
   * @param producer
   */
  public CloudConnector(CloudAccount account, ProducerTemplate producer) {
    this.account = account;
    this.producer = producer;
  }

  /**
   * Sends a new message exchange with given headers and body to ActionListener
   * route
   *
   * @param headers
   * @param body
   */
  public void sendActionExchange(HashMap<String, String> headers, String body) {
    Exchange exchange = new DefaultExchange(this.getProducer().getCamelContext());
    Message message = new DefaultMessage();
    message.setBody(body);

    for (Map.Entry<String, String> entry : headers.entrySet()) {
      message.setHeader(entry.getKey(), entry.getValue());
    }

    exchange.setIn(message);
    this.getProducer().send("direct:actions", exchange);
  }

  /**
   * Gets this cloud connection's cloud account
   *
   * @return this cloud connection's cloud account
   */
  public CloudAccount getAccount() {
    return account;
  }

  /**
   * Gets this cloud connection's producer template
   *
   * @return this cloud connection's producer template
   */
  public ProducerTemplate getProducer() {
    return producer;
  }

}
