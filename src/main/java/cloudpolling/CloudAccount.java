package cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

/**
 * Represents a cloud storage service account.
 *
 * @author tlarrue
 *
 */
public class CloudAccount {

  public int ID;
  public Type type;
  public PollingProject parent;
  public File configFile;
  public File configTemplate;

  /**
   * Enumerates possible types of cloud accounts handled by this application.
   *
   * @author tlarrue
   *
   */
  public enum Type {
    BOX, DROPBOX, GOOGLEDRIVE
  }

  public static HashMap<Type, File> TEMPLATES;
  static {
    HashMap<CloudAccount.Type, File> map = new HashMap<CloudAccount.Type, File>();

    File boxTemplate = new File("src/main/resources/templates/box.properties");
    map.put(CloudAccount.Type.BOX, boxTemplate);

    File dropBoxTemplate = new File("src/main/resources/templates/dropbox.properties");
    map.put(CloudAccount.Type.DROPBOX, dropBoxTemplate);

    File googleDriveTemplate = new File("src/main/resources/templates/googledrive.properties");
    map.put(CloudAccount.Type.GOOGLEDRIVE, googleDriveTemplate);

    TEMPLATES = map;
  }

  /**
   * Constructs a cloud account from its id, parent project, and account type.
   *
   * @param id
   * @param parent
   * @param type
   */
  public CloudAccount(int id, PollingProject parent, CloudAccount.Type type) {
    this.ID = id;
    this.type = type;
    this.parent = parent;
    this.configFile = defineConfigFile(id, parent);
    this.configTemplate = TEMPLATES.get(type);
  }

  /**
   * Constructs a cloud account from its id and parent project assuming the
   * associated configuration file already exists.
   *
   * @param id
   * @param parent
   */
  public CloudAccount(int id, PollingProject parent) {
    this.ID = id;
    this.parent = parent;
    this.configFile = defineConfigFile(id, parent);
  }

  /**
   * Gets this cloud account's ID.
   *
   * @return the ID of this cloud account.
   */
  public int getID() {
    return ID;
  }

  /**
   * Gets this cloud account's parent project.
   *
   * @return parent PollingProject object of this cloud account.
   */
  public PollingProject getParent() {
    return parent;
  }

  /**
   * Gets the configuration file of this cloud account.
   *
   * @return the configuration file of this cloud account.
   */
  public File getConfigFile() {
    return configFile;
  }

  /**
   * Gets the configuration file template for this cloud account.
   *
   * @return the configuration file template for this cloud account.
   */
  private File getConfigTemplate() {
    return configTemplate;
  }

  /**
   * Creates a configuration file using template if it does not already exist.
   * If it does exist, it will validate the fields and report if further action
   * is needed to configure the account.
   */
  public boolean setConfiguration() {
    boolean fileCreated = false;
    boolean pollReady = false;

    try {
      fileCreated = this.getConfigFile().createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (!fileCreated) {

      if (configsValid()) {

        System.out.println("Configuration file for " + this.getParent().getName() + "/acct" + this.getID()
            + " already exists and has validated.");
        setType();
        pollReady = true;

      } else {

        System.out.println("Configuration file for " + this.getParent().getName() + "/acct" + this.getID()
            + " already exists, but is not valid. Please check fields: " + this.getConfigFile().getAbsolutePath());
      }

    } else {

      System.out.println("New file has been created: " + this.getConfigFile().getAbsolutePath());
      copyTemplateToConfigFile();
    }

    return pollReady;
  }

  /**
   * Copies appropriate template to the account's configuration file.
   */
  public void copyTemplateToConfigFile() {
    try {
      InputStream inStream = new FileInputStream(this.getConfigTemplate());
      Properties temp = new Properties();
      temp.load(inStream);

      temp.setProperty("configID", Integer.toString(this.getID()));

      FileOutputStream outStream = new FileOutputStream(this.getConfigFile());
      temp.store(outStream,
          "Properties for " + this.getType().toString() + " connection account " + Integer.toString(this.getID())
              + " for project "
              + this.getParent().getName());
      outStream.close();

      System.out.println("Account configuration template '" + this.getConfigTemplate().getName()
          + "' has been copied to configuration file '" + this.getConfigFile().getAbsolutePath()
          + "'. \nPlease fill out.");

    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  /**
   * Defines the configuration file for this cloud account.
   *
   * @param id
   * @param parent
   * @return the configuration file for this cloud account.
   */
  public File defineConfigFile(int id, PollingProject parent) {
    String configDir = parent.getAccountsDir().getAbsolutePath();
    Path configPath = Paths.get(configDir, "acct" + Integer.toString(id) + ".properties");
    File file = new File(configPath.toString());

    return file;
  }

  /**
   * Returns true if configuration file is filled out correctly for this cloud
   * account.
   *
   * @return state of this cloud account's configuration file
   */
  private boolean configsValid() {
    Properties config = getConfiguration();
    setType();

    boolean fieldsOK = true;

    switch (this.getType()) {

    case BOX:
      // check if private key file exists
      String privateKeyFilename = config.getProperty("privateKeyFile");
      File privateKeyFile = new File(privateKeyFilename);
      fieldsOK = privateKeyFile.exists();
      break;

    case GOOGLEDRIVE:
      // check if client secret JSON file exists
      String clientSecretFileName = config.getProperty("clientSecretFile");
      File clientSecretFile = new File(clientSecretFileName);
      fieldsOK = clientSecretFile.exists();
      break;

    default:
      break;

    }

    // For all account types, check that all properties are filled out
    for (Object value : config.values()) {
      if (value.toString().equals("FILLHERE")) {
        fieldsOK = false;
        break;
      }
    }

    return fieldsOK;
  }

  /**
   * Edits given key of this cloud account's configuration file
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
      config.store(outStream,
          "Properties for box connection account " + Integer.toString(this.ID) + " for project "
              + this.getParent().getName());
      outStream.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

    setType();
  }

  /**
   * Reads the given field value from this cloud account's configuration file.
   *
   * @param key
   * @return field value from account's configuration file
   */
  public String readConfiguration(String key) {
    Properties config = getConfiguration();
    return config.getProperty(key);
  }

  /**
   * Sets type for this cloud account from its configuration file.
   */
  private void setType() {
    String type = readConfiguration("configType");
    this.type = CloudAccount.Type.valueOf(type.toUpperCase());
    this.configTemplate = TEMPLATES.get(this.getType());
  }

  /**
   * Gets properties object loaded from this account's configuration file.
   *
   * @return properties from this cloud account's configuration file.
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
   * Gets the pollToken field from this cloud account's configuration file.
   *
   * @return current poll token of this cloud account
   */
  public String getPollToken() {
    return readConfiguration("pollToken");
  }

  /**
   * Gets account type of this cloud account
   *
   * @return account type of this cloud account
   */
  public CloudAccount.Type getType() {
    return type;
  }

}
