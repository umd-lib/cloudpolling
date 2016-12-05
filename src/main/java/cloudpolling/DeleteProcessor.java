package cloudpolling;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

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
    String cloudPath = exchange.getIn().getHeader("source_path", String.class);
    String fileName = Paths.get(this.getProject().getSyncFolder(), "acct" + accountID, cloudPath).toString();
    File file = new File(fileName);

    String type = exchange.getIn().getHeader("source_type", String.class);
    if (type == "file") {
      boolean fileDeleted = file.delete();
      if (fileDeleted) {
        log.info("File deleted: " + fileName);
      } else {
        log.info("WARNING: Could not delete file: " + fileName);
      }
    } else {
      try {
        // Deleting the directory recursively.
        delete(fileName);
        log.info("Directory & its children deleted: " + fileName);
      } catch (IOException e) {
        log.info("WARNING: Could not delete directory: " + fileName);
      }

    }

    // create JSON for Solr Exchange
    String sourceID = exchange.getIn().getHeader("source_id", String.class);
    log.info("Creating JSON for deleting cloud file with ID:" + sourceID);
    String delete_json = "{ \"delete\" : { \"id\" : \"" + sourceID + "\" } }";
    exchange.getIn().setBody(delete_json);

  }

  /**
   * Delete a file or a directory and its children.
   *
   * @param file
   *          The directory to delete.
   * @throws IOException
   *           Exception when problem occurs during deleting the directory.
   */
  private static void delete(String directoryName) throws IOException {

    Path directory = Paths.get(directoryName);
    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

      @Override
      public FileVisitResult visitFile(Path file,
          BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc)
          throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  public PollingProject getProject() {
    return this.project;
  }

  public void setProject(PollingProject p) {
    this.project = p;
  }

}
