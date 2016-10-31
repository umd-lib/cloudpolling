package edu.umd.lib.cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;

import edu.umd.lib.cloudpolling.CloudAccount.AccountType;

public class PollingProject implements java.io.Serializable {

  /**
   * Class that hold all configuration information for a polling project and its
   * associated accounts
   */

  private static final long serialVersionUID = -5188789241527583188L;
  public static final String CONFIG_TEMPLATE_NAME = "resources/templates/project.properties";

  public String name; // name of project
  public Properties config; // configuration of project
  public String projectFolderName; // folder of project's config files
  public String acctsFolderName; // folder of project's accounts' config files
  public HashMap<Integer, CloudAccount> accounts; // accounts that belong to
                                                  // this project
  // public List<CloudAccount> accounts;

  public PollingProject(String projectName, String configDir) {
    /**
     * Initializes instance variables
     */

    this.name = projectName;
    this.config = new Properties();

    Path projectPath = Paths.get(configDir, this.name);
    this.projectFolderName = projectPath.toString();

    Path accountsPath = Paths.get(projectPath.toString(), "accts");
    this.acctsFolderName = accountsPath.toString();

    // this.accounts = new ArrayList<CloudAccount>();
    this.accounts = new HashMap<Integer, CloudAccount>();
  }

  public boolean setProperties() {
    /**
     * Reads configuration files & sets 'config' field
     */

    boolean propertiesDefined = false;

    // Create project configuration directory
    File projectDir = new File(this.projectFolderName);
    if (!projectDir.exists()) {
      projectDir.mkdir();
    }

    // Create project's accounts configuration directory
    File acctsDir = new File(this.acctsFolderName);
    if (!acctsDir.exists()) {
      acctsDir.mkdir();
    }

    // Define path for project configuration file
    Path configPath = Paths.get(this.projectFolderName, name + ".properties");
    String configFilename = configPath.toString();
    File configFile = new File(configFilename);

    try {
      if (configFile.createNewFile()) {
        // If file does NOT exist, create it, copy template to it, but return
        // false (properties not defined for this account)
        File template = new File(CONFIG_TEMPLATE_NAME);
        InputStream inputStream = new FileInputStream(template);
        Properties temp = new Properties();
        temp.load(inputStream);

        FileOutputStream outputStream = new FileOutputStream(configFile);
        temp.store(outputStream, "Properties for polling project " + name);
        outputStream.close();

        System.out.println("File is created: " + configFilename + ". \nPlease fill out and rerun 'new' command.");

      } else {

        // If file exists, read properties
        InputStream inputStream = new FileInputStream(configFile);
        this.config.load(inputStream);

        // check that property fields are valid
        propertiesDefined = checkProperties(this.config);

      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return propertiesDefined;

  }

  public void addAccount(AccountType accountType) {
    /**
     * Creates a new cloud account and adds that account to this project's
     * accounts field. If account configuration cannot be resolved, it does NOT
     * add the account to accounts.
     */

    // define ID number for new account
    int newID;
    if (this.accounts.isEmpty()) {
      newID = 1;
    } else {
      newID = Collections.max(accounts.keySet()) + 1;
    }

    // create new cloud account object & add to accounts field
    CloudAccount newAccount = new CloudAccount(newID, accountType);
    boolean addThisAccount = newAccount.setProperties(this.acctsFolderName);
    if (addThisAccount) {
      this.accounts.put(newID, newAccount);
      System.out.println("New " + accountType.toString() + " account added to project " + this.name + " with ID: "
          + Integer.toString(newAccount.getID()));
    }

  }

  private Boolean checkProperties(Properties props) {
    /**
     * Confirms if fields in props file are valid for a project.
     */

    String syncFolderName = props.get("syncFolder").toString();
    File syncFolder = new File(syncFolderName);
    Boolean syncFolderExists = syncFolder.exists() && syncFolder.isDirectory();

    Boolean fieldsOK = syncFolderExists;

    // TODO: add solr fields

    return fieldsOK;
  }

  public HashMap<Integer, CloudAccount> getAllAccounts() {
    return this.accounts;
  }

  public String getName() {
    return this.name;
  }

  public String getSyncFolder() {
    return this.config.getProperty("syncFolder");
  }

}
