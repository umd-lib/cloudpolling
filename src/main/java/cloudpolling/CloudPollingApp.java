package cloudpolling;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;

import cloudpolling.CloudAccount.AccountType;

public class CloudPollingApp {

  /***
   * Main application to poll & handle events from multiple cloud storage
   * services
   *
   * @author tlarrue
   *
   */

  public static enum Command {
    NEW, ADD, POLL
  }

  public static class CommandSet {
    public Command COMMAND;
    public String PROJECTNAME;
    public AccountType ACCT_TYPE;
  }

  public static void main(String[] args) throws Exception {

    /**
     * Perform command from command line arguments
     */

    // get parameters
    CommandSet COMMANDS = parseArguments(args);
    String CONFIGDIR = System.getenv().get("CPOLL_CONFIGS");

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

    }

  }

  public static void createNewPollingProject(String projectName, String topConfigDir) {
    /**
     * Sets up a new polling project with 0 accounts.
     */

    PollingProject project = loadProject(projectName, topConfigDir);

    if (project != null) {
      System.out.println("New project successfully created: " + projectName);
    }
  }

  public static void addAccountToPollingProject(String projectName, String topConfigDir, AccountType accountType) {
    /**
     * Adds a CloudAccount to an existing PollingProject object.
     */

    PollingProject project = loadProject(projectName, topConfigDir);

    if (project == null) {
      System.out.println("ERROR: Please check configuration setup for project: " + projectName);
      System.exit(1);
    } else {
      System.out.println("Project found: " + projectName);
      project.addAccount(accountType);
    }

  }

  public static void pollPollingProject(String projectName, String topConfigDir) throws Exception {
    /**
     * Starts CamelContext with local routing for a PollingProject
     */

    PollingProject project = loadProject(projectName, topConfigDir);

    if (project != null) {

      System.out.println("Project found: " + projectName);

      CamelContext context = new DefaultCamelContext();
      LocalRouter localRoutes = new LocalRouter(project, context.createProducerTemplate());
      context.addRoutes(localRoutes);

      context.start();
      Thread.sleep(1000 * 60 * 5); // 5 min
      context.stop();

    } else {
      System.out.println("ERROR: Please check configuration setup for project: " + projectName);
      System.exit(1);
    }

  }

  public static PollingProject loadProject(String projectName, String topConfigDir) {
    /**
     * Creates a PollingProject object if its configuration file is valid
     */

    PollingProject project = new PollingProject(projectName, topConfigDir);
    project.setConfiguration();

    return project;
  }

  public static CommandSet parseArguments(String[] args) {
    /**
     * Parses command line arguments to a CommandSet object
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
