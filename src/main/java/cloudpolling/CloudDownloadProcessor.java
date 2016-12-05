package cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.json.JSONObject;
import org.xml.sax.SAXException;

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

  /**
   * Processes message exchange by creating a JSON for SolrUpdater exchange
   */
  public void process(Exchange exchange) throws Exception {

    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    String sourceName = exchange.getIn().getHeader("source_name", String.class);
    String sourcePath = exchange.getIn().getHeader("source_path", String.class);
    String parentID = exchange.getIn().getHeader("parent_id", String.class);
    String sourceType = exchange.getIn().getHeader("source_type", String.class);
    String accountID = exchange.getIn().getHeader("account_id", String.class);
    String accountType = exchange.getIn().getHeader("account_type", String.class);

    String destPath = Paths.get(this.getProject().getSyncFolder(), "acct" + accountID, sourcePath).toString();
    File destItem = new File(destPath);

    JSONObject json = new JSONObject();
    json.put("id", sourceID);
    json.put("name", sourceName);
    json.put("path", destPath);
    json.put("parent_id", parentID);
    json.put("account_type", accountType);
    json.put("account_id", accountID);

    if (sourceType == "file") {
      Tika tika = new Tika();

      String metadata = exchange.getIn().getHeader("metadata", String.class);

      json.put("type", tika.detect(destItem));
      json.put("content", parseToPlainText(destItem));
      json.put("metadata", metadata);
    }

    exchange.getIn().setBody("[" + json.toString() + "]");

  }

  /***
   * Convert the File into Plain Text file
   *
   * @param file
   * @return
   * @throws IOException
   * @throws SAXException
   * @throws TikaException
   */
  public String parseToPlainText(File file) throws IOException, SAXException, TikaException {
    BodyContentHandler handler = new BodyContentHandler();

    AutoDetectParser parser = new AutoDetectParser();
    Metadata metadata = new Metadata();
    try {
      InputStream targetStream = new FileInputStream(file.getAbsolutePath());
      parser.parse(targetStream, handler, metadata);
      return handler.toString();
    } catch (Exception e) {
      e.printStackTrace();
      return "Empty String";
    }
  }

}
