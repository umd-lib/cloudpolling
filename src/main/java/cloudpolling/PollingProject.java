package cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

import cloudpolling.CloudAccount.AccountType;

public class PollingProject {

  /**
   * Class that represents a polling project (which is the parents to many cloud
   * accounts
   */

  public static final String CONFIG_TEMPLATE_NAME = "src/main/resources/templates/project.properties";

  public String NAME;
  public File CONFIG_FILE;
  public File PROJECT_DIR;
  public File ACCTS_DIR;

  public PollingProject(String name, String configDir) {
    /**
     * Constructor - Initializes instance variables & validates configuration
     */

    this.NAME = name;
    this.CONFIG_FILE = defineConfigFile(name, configDir);
    this.PROJECT_DIR = this.CONFIG_FILE.getParentFile();
    this.ACCTS_DIR = new File(Paths.get(this.PROJECT_DIR.getAbsolutePath(), "accts").toString());
  }

  public File defineConfigFile(String name, String configDir) {
    /**
     * Returns the project's configuration file
     */

    Path configPath = Paths.get(configDir, name, name + ".properties");
    String configFileName = configPath.toString();
    File configFile = new File(configFileName);

    return configFile;
  }

  public boolean configsValid() {
    /**
     * Returns True if configuration is set correctly for this project
     */

    boolean fieldsOK = false;
    Properties config = getConfiguration();

    String syncFolderName = config.getProperty("syncFolder");
    File syncFolder = new File(syncFolderName);
    boolean syncFolderExists = syncFolder.exists() && syncFolder.isDirectory();

    fieldsOK = syncFolderExists; // TODO: add solr fields to configuration

    return fieldsOK;
  }

  public Properties getConfiguration() {
    /**
     * Returns Properties object loaded from this account's configuration file
     */

    InputStream inStream;
    Properties config = new Properties();

    try {
      inStream = new FileInputStream(this.CONFIG_FILE);
      config.load(inStream);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

    return config;
  }

  public void setConfiguration() {
    /**
     * Creates file structure & configuration file using template if it does not
     * already exist. If it does exist, it will validate the fields and report
     * if further action is needed to configure the account.
     */

    boolean fileCreated = false;

    // Create project configuration directory if it doesn't exist
    if (!this.PROJECT_DIR.exists()) {
      this.PROJECT_DIR.mkdir();
    }

    // Create project's accounts configuration directory if it doesn't exist
    if (!this.ACCTS_DIR.exists()) {
      this.ACCTS_DIR.mkdir();
    }

    // Create project's configuration file it doesn't exist
    try {
      fileCreated = CONFIG_FILE.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (!fileCreated) {
      if (configsValid()) {

        System.out.println("Configuration file for project '" + this.NAME + "' already exists and has validated.");

      } else {

        System.out.println("Configuration file for " + this.NAME
            + " already exists, but is not valid. Please check fields: " + this.CONFIG_FILE.getAbsolutePath());
      }

    } else {

      System.out.println("New file has been created: " + this.CONFIG_FILE.getAbsolutePath());

      copyTemplateToConfigFile();
    }

  }

  public void copyTemplateToConfigFile() {
    /**
     * Copies appropriate template to the account's configuration file.
     */

    File template = new File(CONFIG_TEMPLATE_NAME);

    try {
      InputStream inStream = new FileInputStream(template);
      Properties temp = new Properties();
      temp.load(inStream);

      FileOutputStream outStream = new FileOutputStream(this.CONFIG_FILE);
      temp.store(outStream, "Properties for polling project " + this.NAME);
      outStream.close();

      System.out.println("Project configuration template '" + CONFIG_TEMPLATE_NAME
          + "' has been copied to configuration file '" + this.CONFIG_FILE.getAbsolutePath()
          + "'. \nPlease fill out.");

    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public void addAccount(AccountType accountType) {
    /**
     * Creates a new CloudAccount configuration file within accountsDir.
     */

    int newID = 0;

    // Define ID for new account
    ArrayList<Integer> ids = getAccountIds();
    if (ids.isEmpty()) {
      newID = 1;
    } else {
      newID = Collections.max(ids) + 1;
    }

    // Create new cloud account object & set its configuration
    CloudAccount account = new CloudAccount(newID, this, accountType);
    account.setConfiguration();
  }

  public String getName() {
    /**
     * Getter for NAME
     */
    return this.NAME;
  }

  public ArrayList<Integer> getAccountIds() {
    /**
     * Gets all account id's from accounts configuration directory
     */

    ArrayList<Integer> ids = new ArrayList<Integer>();
    String filename = null;

    File[] files = this.ACCTS_DIR.listFiles();

    if (files != null) {
      for (File file : files) {

        filename = file.getName();

        if (filename.endsWith(".properties")) {
          ids.add(Integer.parseInt(filename.split(".properties")[0].split("acct", 2)[1]));
        }
      }
    }

    return ids;
  }

  public String readConfiguration(String key) {
    /**
     * Returns the given field value from the configuration file.
     */

    Properties config = getConfiguration();
    return config.getProperty(key);
  }

  public String getSyncFolder() {
    /**
     * Returns syncFolder field from configuration file
     */
    return readConfiguration("syncFolder");
  }

  public File getAcctsDir() {
    /**
     * Getter for ACCTS_DIR
     */
    return this.ACCTS_DIR;
  }

}
