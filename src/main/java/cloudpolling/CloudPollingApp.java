package cloudpolling;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;

/**
 * Main application to poll and handle events from multiple cloud storage
 * services
 *
 * @author tlarrue
 *
 */
public class CloudPollingApp {

  /**
   * Enumerates possible commands for cloudpolling application.
   *
   * @author tlarrue
   *
   */
  private static enum Command {
    NEW, ADD, POLL, RESET, BOXAPPUSER
  }

  /**
   * Represents a set of command line arguments.
   *
   * @author tlarrue
   *
   */
  private static class CommandSet {
    public Command COMMAND;
    public String PROJECTNAME;
    public CloudAccount.Type ACCT_TYPE;
    public String ACCT_NAME;
  }

  /**
   * Perform command from command line arguments
   *
   * @param args
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {

    // get parameters
    CommandSet COMMANDS = parseArguments(args);
    String CONFIGDIR = System.getenv().get("CPOLL_CONFIGS");

    // check that configuration directory is set
    if (CONFIGDIR == null) {
      System.out.println("ERROR: Please set your $CPOLL_CONFIGS environment variable.");
      System.exit(1);
    }

    // perform actions based on parameters
    switch (COMMANDS.COMMAND) {

    case NEW:

      createNewPollingProject(COMMANDS.PROJECTNAME, CONFIGDIR);
      break;

    case ADD:

      addAccountToPollingProject(COMMANDS.PROJECTNAME, CONFIGDIR, COMMANDS.ACCT_TYPE);
      break;

    case POLL:

      pollPollingProject(COMMANDS.PROJECTNAME, CONFIGDIR);
      break;

    case RESET:

      resetPollingProject(COMMANDS.PROJECTNAME, CONFIGDIR);
      break;

    case BOXAPPUSER:

      createBoxAppUser(COMMANDS.PROJECTNAME, COMMANDS.ACCT_NAME, CONFIGDIR);

    }

  }

  /**
   * Creates a Box App User with app user name and enterprise ID specified in
   * account's configuration file.
   *
   * @param projectName
   * @param accountName
   * @param topConfigDir
   */
  private static void createBoxAppUser(String projectName, String accountName, String topConfigDir) {

    PollingProject project = loadProject(projectName, topConfigDir);

    int accountID = Integer.parseInt(accountName.split("acct")[1]);
    CloudAccount account = new CloudAccount(accountID, project);
    ProducerTemplate dummy = new DefaultCamelContext().createProducerTemplate();
    BoxConnector box = new BoxConnector(account, dummy);
    try {
      String userID = box.createAppUser();
      account.updateConfiguration("userID", userID);
      System.out.println("Account configuration file has been updated with app user ID.");
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  /**
   * Resets last poll date and poll tokens for all accounts in given polling
   * project.
   *
   * @param projectName
   * @param topConfigDir
   */
  private static void resetPollingProject(String projectName, String topConfigDir) {

    PollingProject project = loadProject(projectName, topConfigDir);

    System.out.println("Resetting all poll tokens...");

    project.updateConfiguration("lastPoll", "1900/01/01-00:00:00");
    System.out.println("'" + projectName + "' project's poll token reset.");

    ArrayList<Integer> ids = project.getAccountIds();
    for (Integer id : ids) {
      CloudAccount acct = new CloudAccount(id, project);
      acct.updateConfiguration("pollToken", "0");
      System.out.println("\tAccount " + Integer.toString(id) + "'s poll token reset.");
    }

  }

  /**
   * Creates a new polling project.
   *
   * @param projectName
   * @param topConfigDir
   */
  private static void createNewPollingProject(String projectName, String topConfigDir) {
    loadProject(projectName, topConfigDir);
  }

  /**
   * Adds a cloud account to an existing polling project.
   *
   * @param projectName
   * @param topConfigDir
   * @param accountType
   */
  private static void addAccountToPollingProject(String projectName, String topConfigDir,
      CloudAccount.Type accountType) {
    PollingProject project = loadProject(projectName, topConfigDir);
    project.addAccount(accountType);
  }

  /**
   * Polls updates for all cloud accounts associated with given polling project.
   *
   * @param projectName
   * @param topConfigDir
   * @throws Exception
   */
  private static void pollPollingProject(String projectName, String topConfigDir) throws Exception {
    PollingProject project = loadProject(projectName, topConfigDir);

    CamelContext context = new DefaultCamelContext();
    SyncRouter routes = new SyncRouter(project, context.createProducerTemplate());
    context.addRoutes(routes);

    context.start();
    Thread.sleep(1000 * 60 * 5); // 5 minutes
    context.stop();

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd-HH:mm:ss");
    Date dateobj = new Date();
    project.updateConfiguration("lastPoll", sdf.format(dateobj));
  }

  /**
   * Constructs a polling project object and checks its configuration.
   *
   * @param projectName
   * @param topConfigDir
   * @return a polling project
   */
  private static PollingProject loadProject(String projectName, String topConfigDir) {

    System.out.println("Loading polling project '" + projectName + "'...");
    PollingProject project = new PollingProject(projectName, topConfigDir);
    project.setConfiguration();

    return project;
  }

  /**
   * Parses command line arguments to a CommandSet object
   *
   * @param args
   * @return command set given by the arguments
   */
  private static CommandSet parseArguments(String[] args) {

    String USAGE = "\n new <projectname> : creates a new polling project"
        + "\n add <projectname> <acct_type> : adds a cloud account to a project (types: Box, DropBox, Drive)"
        + "\n poll <projectname> : polls all accounts in a project and syncs account folder with local system"
        + "\n reset <projectname> : resets poll tokens on all accounts associated with given project"
        + "\n boxappuser <projectname> <acct_name> : creates a box app user for given account - enterpriseID and appUserName must be filled out in account's configuration file";

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

      if (set.COMMAND == Command.BOXAPPUSER) {
        set.ACCT_NAME = args[2];
      } else {
        set.ACCT_TYPE = CloudAccount.Type.valueOf(args[2].toUpperCase());
      }

      break;

    default:

      System.out.println("Arguments not understood. Proper Usage is: " + USAGE);
      break;
    }

    return set;
  }
}
