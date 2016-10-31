package edu.umd.lib.cloudpolling;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;

import edu.umd.lib.cloudpolling.CloudAccount.AccountType;

public class CloudPollingApp {

  /***
   * Main application to poll & handle events from multiple cloud storage
   * services
   *
   * @author tlarrue
   *
   */

  private static String CONFIGDIR = null;

  public enum Command {
    NEW, ADD, POLL
  }

  private static class CommandSet {
    public Command COMMAND;
    public String PROJECTNAME;
    public AccountType ACCT_TYPE;
  }

  public static void main(String[] args) throws Exception {

    /**
     * Perform command from command line arguments
     */

    // get parameters
    CommandSet thisSet = parseArguments(args);
    CONFIGDIR = System.getenv().get("CPOLL_CONFIGS");

    if (CONFIGDIR == null) {
      System.out.println("ERROR: Please set your $CPOLL_CONFIGS environment variable.");
      System.exit(1);
    }

    // perform actions based on parameters
    switch (thisSet.COMMAND) {

    case NEW:

      createNewPollingProject(thisSet.PROJECTNAME, CONFIGDIR);
      break;

    case ADD:

      addAccountToPollingProject(thisSet.PROJECTNAME, thisSet.ACCT_TYPE);
      break;

    case POLL:

      pollPollingProject(thisSet.PROJECTNAME);
      break;

    }

  }

  private static void createNewPollingProject(String projectname, String topdir) {
    /**
     * Sets up a new polling project with 0 accounts, then serializes project
     * object for future use.
     */

    PollingProject project = new PollingProject(projectname, topdir);
    Boolean projectComplete = project.setProperties();
    if (projectComplete) {
      System.out.println("New project successfully created: " + projectname);
      serializeProject(project);
    }

  }

  private static void addAccountToPollingProject(String projectname, AccountType accttype) {
    /**
     * Adds a CloudAccount to an existing PollingProject object.
     */

    PollingProject project = loadProject(projectname);
    project.addAccount(accttype);
    serializeProject(project);

  }

  private static void pollPollingProject(String projectname) throws Exception {
    /**
     * Starts CamelContext with local routing for a PollingProject
     */

    PollingProject project = loadProject(projectname);

    CamelContext context = new DefaultCamelContext();
    LocalRouter localRoutes = new LocalRouter(project, context.createProducerTemplate());
    context.addRoutes(localRoutes);

    context.start();
    Thread.sleep(1000 * 60 * 15); // 15 min
    context.stop();

  }

  private static void serializeProject(PollingProject project) {
    /**
     * Serializes & saves a PollingProject object.
     */

    try {
      String filename = getProjectSerial(project.getName());
      FileOutputStream fileOutputStream = new FileOutputStream(filename);
      ObjectOutputStream out = new ObjectOutputStream(fileOutputStream);
      out.reset();
      out.writeObject(project);
      out.close();
      fileOutputStream.close();
      System.out.printf("Serialized data is saved in " + filename);

    } catch (IOException i) {
      i.printStackTrace();
    }

  }

  private static PollingProject loadProject(String projectname) {
    /**
     * Loads a serialized PollingProject object.
     */

    PollingProject project = null;

    try {
      ObjectInputStream in = new ObjectInputStream(new FileInputStream(getProjectSerial(projectname)));
      project = (PollingProject) in.readObject();
      in.close();

    } catch (Exception e) {
      e.printStackTrace();
    }

    return project;

  }

  private static String getProjectSerial(String projectName) {
    /**
     * Serialized PollingProject is located in project's configuration folder
     */
    Path projectPath = Paths.get(CONFIGDIR, projectName, projectName + ".ser");
    String projectSerialName = projectPath.toString();

    return projectSerialName;
  }

  private static CommandSet parseArguments(String[] args) {
    /**
     * Parses command line arguments to an Arg class
     */

    String USAGE = "\n new <projectname> : creates a new polling project"
        + "\n add <projectname> <acct_type> : adds a cloud account to a project (types: Box, DropBox, Drive)"
        + "\n poll <projectname> : polls all accounts in a project and syncs account folder with local system";

    CommandSet set = new CommandSet();

    int numArgs = args.length;

    switch (numArgs) {

    case 2:

      set.COMMAND = Command.valueOf(args[0].toUpperCase());
      set.PROJECTNAME = args[1];
      break;

    case 3:

      set.COMMAND = Command.valueOf(args[0].toUpperCase());
      set.PROJECTNAME = args[1];
      set.ACCT_TYPE = AccountType.valueOf(args[2].toUpperCase());
      break;

    default:

      System.out.println("Arguments not understood. Proper Usage is: " + USAGE);
      break;

    }

    return set;

  }

}
