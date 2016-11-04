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

public class CloudAccount {

  /**
   * Class that represents a cloud service account.
   *
   * Account Types handled : Box, DropBox (soon), GoogleDrive (soon)
   */

  public int ID;
  public AccountType TYPE;
  public PollingProject PARENT;
  public File CONFIG_FILE;
  public File CONFIG_TEMPLATE;

  public static enum AccountType {
    BOX, DROPBOX, GOOGLEDRIVE
  }

  // TODO: add dropbox & google drive config templates
  public static HashMap<AccountType, File> templates;
  static {
    HashMap<AccountType, File> map = new HashMap<AccountType, File>();

    File boxTemplate = new File("src/main/resources/templates/box.properties");
    map.put(AccountType.BOX, boxTemplate);

    templates = map;
  }

  public CloudAccount(int id, PollingProject parent, AccountType type) {
    /**
     * Constructor for a brand new cloud account
     */

    this.ID = id;
    this.TYPE = type;
    this.PARENT = parent;
    this.CONFIG_FILE = defineConfigFile(id, parent);
    this.CONFIG_TEMPLATE = templates.get(type);
  }

  public CloudAccount(int id, PollingProject parent) {
    /**
     * Constructor for cloud account that is assumed to already exist
     */

    this.ID = id;
    this.PARENT = parent;
    this.CONFIG_FILE = defineConfigFile(id, parent);
  }

  public void setConfiguration() {
    /**
     * Creates a configuration file using template if it does not already exist.
     * If it does exist, it will validate the fields and report if further
     * action is needed to configure the account.
     */

    boolean fileCreated = false;

    try {
      fileCreated = CONFIG_FILE.createNewFile();
    } catch (IOException e) {
      e.printStackTrace();
    }

    if (!fileCreated) {

      if (configsValid()) {

        System.out.println("Configuration file for " + this.PARENT.getName() + "/acct" + this.ID
            + " already exists and has validated.");

        setType();

      } else {

        System.out.println("Configuration file for " + this.PARENT.getName() + "/acct" + this.ID
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

    try {
      InputStream inStream = new FileInputStream(this.CONFIG_TEMPLATE);
      Properties temp = new Properties();
      temp.load(inStream);

      temp.setProperty("configID", Integer.toString(this.ID));

      FileOutputStream outStream = new FileOutputStream(this.CONFIG_FILE);
      temp.store(outStream,
          "Properties for box connection account " + Integer.toString(this.ID) + " for project "
              + this.PARENT.getName());
      outStream.close();

      System.out.println("Account configuration template '" + this.CONFIG_TEMPLATE.getName()
          + "' has been copied to configuration file '" + this.CONFIG_FILE.getAbsolutePath()
          + "'. \nPlease fill out.");

    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

  }

  public File defineConfigFile(int id, PollingProject parent) {
    /**
     * Returns the Configuration File
     */

    String configDir = parent.getAcctsDir().getAbsolutePath();
    Path configPath = Paths.get(configDir, "acct" + Integer.toString(id) + ".properties");
    File file = new File(configPath.toString());

    return file;
  }

  private boolean configsValid() {
    /**
     * Confirms if fields in props are valid for an account.
     */

    Properties config = getConfiguration();
    setType();

    boolean fieldsOK = true;

    switch (this.TYPE) {

    case BOX:
      // check if private key file exists
      // TODO: be more robust here
      String privateKeyFilename = config.getProperty("privateKeyFile");
      File privateKeyFile = new File(privateKeyFilename);
      fieldsOK = privateKeyFile.exists();

      for (Object value : config.values()) {
        if (value.toString() == "FILLHERE") {
          fieldsOK = false;
          break;
        }
      }
      break;

    case DROPBOX:
      // TODO: add functionality for dropbox
      break;

    case GOOGLEDRIVE:
      // TODO: add functionality for google drive
      break;

    }

    return fieldsOK;
  }

  public void updateConfiguration(String key, String value) {
    /**
     * Edits this account's configuration file
     */

    Properties config = getConfiguration();

    config.setProperty(key, value);

    FileOutputStream outStream;
    try {
      outStream = new FileOutputStream(this.CONFIG_FILE);
      config.store(outStream,
          "Properties for box connection account " + Integer.toString(this.ID) + " for project "
              + this.PARENT.getName());
      outStream.close();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }

    setType();

  }

  public String readConfiguration(String key) {
    /**
     * Returns the given field value from the configuration file.
     */

    Properties config = getConfiguration();
    return config.getProperty(key);
  }

  public void setType() {
    /**
     * Sets TYPE variable from configuration file
     */
    String type = readConfiguration("configType");
    this.TYPE = AccountType.valueOf(type.toUpperCase());
    this.CONFIG_TEMPLATE = templates.get(this.TYPE);
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

  public String getPollToken() {
    /**
     * Returns the pollToken field from this account's configuration file
     */
    return readConfiguration("pollToken");
  }

  public int getID() {
    /**
     * Getter for ID
     */
    return this.ID;
  }

  public AccountType getType() {
    /**
     * Getter for TYPE
     */
    return this.TYPE;
  }

}
