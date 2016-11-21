package cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

/**
 * Represents a polling project that contains many cloud accounts.
 *
 * @author tlarrue
 */
public class PollingProject {

  public static final String CONFIG_TEMPLATE_NAME = "src/main/resources/templates/project.properties";

  public String name;
  public File configFile;
  public File projectDir;
  public File accountsDir;

  /**
   * Constructs a PollingProject from a unique name and directory where all
   * configuration files for cloudpolling projects are stored.
   *
   * @param name
   * @param configDir
   */
  public PollingProject(String name, String configDir) {
    this.name = name;
    this.configFile = new File(Paths.get(configDir, name, name + ".properties").toString());
    this.projectDir = getConfigFile().getParentFile();
    this.accountsDir = new File(Paths.get(getProjectDir().getAbsolutePath(), "accts").toString());
  }

  /**
   * Creates a configuration file using polling project template if it does not
   * already exist. If it does exist, validates the fields and report if further
   * action is needed to configure the account.
   */
  public void setConfiguration() {

    // Create project & accounts configuration directory if it doesn't exist
    if (!this.getAccountsDir().exists()) {
      this.getAccountsDir().mkdirs();
    }

    // Create project's configuration file it doesn't exist
    boolean fileCreated = false;
    try {
      fileCreated = this.getConfigFile().createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (!fileCreated) {
      if (configsValid()) {
        System.out.println("Configuration file for project '" + this.getName() + "' has been validated.");

      } else {
        System.out.println("Configuration file for project '" + this.getName()
            + "' is not valid. Please check fields: " + this.getConfigFile().getAbsolutePath());
      }

    } else {
      System.out.println("New file has been created: " + this.getConfigFile().getAbsolutePath());
      copyTemplateToConfigFile();
    }

  }

  /**
   * Returns true if configuration file is filled out correctly for this polling
   * project.
   *
   * @return state of this polling project's configuration file
   */
  private boolean configsValid() {

    boolean fieldsOK = false;
    Properties config = getConfiguration();

    String syncFolderName = config.getProperty("syncFolder");
    File syncFolder = new File(syncFolderName);
    boolean syncFolderExists = syncFolder.exists() && syncFolder.isDirectory();

    fieldsOK = syncFolderExists; // TODO: add solr fields to configuration

    return fieldsOK;
  }

  /**
   * Copies polling project template to this project's configuration file.
   */
  private void copyTemplateToConfigFile() {

    File template = new File(CONFIG_TEMPLATE_NAME);

    try {
      InputStream inStream = new FileInputStream(template);
      Properties temp = new Properties();
      temp.load(inStream);

      FileOutputStream outStream = new FileOutputStream(this.getConfigFile());
      temp.store(outStream, "Properties for polling project " + this.getName());
      outStream.close();

      System.out.println(
          "Polling project template has been copied to configuration file :" + this.getConfigFile().getAbsolutePath());
      System.out.println("Please fill out.");

    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * Creates a new CloudAccount configuration file within this project's
   * accounts configuration directory.
   *
   * @param accountType
   */
  public void addAccount(CloudAccount.Type accountType) {

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

    System.out
        .println(
            "Added new " + accountType.toString() + "account, 'acct" + Integer.toString(newID)
                + "' to polling project '" + this.getName() + "'.");
  }

  /**
   * Edits this project's file
   *
   * @param key
   * @param value
   */
  public void updateConfiguration(String key, String value) {

    Properties config = getConfiguration();

    config.setProperty(key, value);

    FileOutputStream outStream;
    try {
      outStream = new FileOutputStream(this.getConfigFile());
      config.store(outStream, "Properties for polling project " + this.getName());
      outStream.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  /**
   * Gets all account id's from accounts configuration directory
   *
   * @return list of account id's associated with this polling project
   */
  public ArrayList<Integer> getAccountIds() {

    ArrayList<Integer> ids = new ArrayList<Integer>();
    String filename = null;

    File[] files = this.getAccountsDir().listFiles();

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

  /**
   * Gets properties object from this polling project's configuration file
   *
   * @return configuration properties of this polling project
   */
  public Properties getConfiguration() {

    InputStream inStream;
    Properties config = new Properties();

    try {
      inStream = new FileInputStream(this.getConfigFile());
      config.load(inStream);
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

    return config;
  }

  /**
   * Reads the given field value from this project's configuration file.
   *
   * @param key
   * @return given field value from project's configurationfile
   */
  public String readConfiguration(String key) {
    Properties config = getConfiguration();
    return config.getProperty(key);
  }

  /**
   * Returns syncFolder field from this project's configuration file
   *
   * @return name of the folder synced with project's cloud accounts
   */
  public String getSyncFolder() {
    return readConfiguration("syncFolder");
  }

  /**
   * Gets the configuration directory of this polling project.
   *
   * @return name of the folder that holds this polling project's configuration
   *         files
   */
  public File getProjectDir() {
    return projectDir;
  }

  /**
   * Gets the configuration directory of the cloud accounts associated with this
   * polling project.
   *
   * @return name of the folder that holds this configuration files of the cloud
   *         accounts associated with this polling project
   */
  public File getAccountsDir() {
    return accountsDir;
  }

  /**
   * Gets the name of this polling project.
   *
   * @return name of this polling project
   */
  public String getName() {
    return name;
  }

  /**
   * Gets the configuration file for this polling project.
   *
   * @return configuration file for this polling project
   */
  public File getConfigFile() {
    return configFile;
  }

}
