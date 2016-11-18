package cloudpolling;

import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.impl.DefaultMessage;

public class CloudConnector {

  private CloudAccount account;
  private ProducerTemplate producer;

  public CloudConnector(CloudAccount account, ProducerTemplate producer) {

    this.setAccount(account);
    this.setProducer(producer);

  }

  public void sendActionExchange(HashMap<String, String> headers, String body) {
    /**
     * Sends a new exchange to ActionListener route
     */

    Exchange exchange = new DefaultExchange(this.producer.getCamelContext());
    Message message = new DefaultMessage();

    message.setBody(body);

    for (Map.Entry<String, String> entry : headers.entrySet()) {
      message.setHeader(entry.getKey(), entry.getValue());
    }

    exchange.setIn(message);

    this.producer.send("direct:actions", exchange);

  }

  public CloudAccount getAccount() {
    return this.account;
  }

  public void setAccount(CloudAccount account) {
    this.account = account;
  }

  public ProducerTemplate getProducer() {
    return this.producer;
  }

  public void setProducer(ProducerTemplate producer) {
    this.producer = producer;
  }

}
