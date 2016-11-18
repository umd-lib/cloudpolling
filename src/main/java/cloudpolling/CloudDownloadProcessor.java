package cloudpolling;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;

public class CloudDownloadProcessor implements Processor {

  PollingProject project;

  public CloudDownloadProcessor(PollingProject project) {
    setProject(project);
  }

  public PollingProject getProject() {
    return this.project;
  }

  public void setProject(PollingProject p) {
    this.project = p;
  }

  public void process(Exchange exchange) throws Exception {

  }

}
