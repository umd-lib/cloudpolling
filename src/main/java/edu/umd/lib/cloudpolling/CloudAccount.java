package src.main.java.edu.umd.lib.cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

public class CloudAccount {

  /**
   * Class that holds configuration (includes authentication info for an API
   * connection) info for a cloud service account.
   *
   * Account Types handled : Box, DropBox (soon), GoogleDrive (soon)
   */
  public static enum AccountType {
    BOX, DROPBOX, GOOGLEDRIVE
  }

  // TODO: add dropbox & google drive config templates
  public static HashMap<AccountType, File> templates;
  static {
    HashMap<AccountType, File> map = new HashMap<AccountType, File>();

    File boxTemplate = new File("resources/templates/box.properties");
    map.put(AccountType.BOX, boxTemplate);

    templates = map;
  }

  public int id; // ID of this account (auto-generated)
  public File configFile;
  public AccountType type; // type of this account
  public File configTemplate; // template file account configuration
  public Properties config; // configuration fields for this account

  public CloudAccount(int ID, AccountType accountType, String configDir) {
    /**
     * Constructor for creating new CloudAccount of type accountType
     */

    this.id = ID;
    this.type = accountType;
    this.config = new Properties();
    this.configTemplate = templates.get(this.type);

    // Define path of account configuration file
    Path configPath = Paths.get(configDir, "acct" + Integer.toString(this.id) + ".properties");
    String configFilename = configPath.toString();
    this.configFile = new File(configFilename);
  }

  public CloudAccount(int ID, String configDir) {
    /**
     * Constructor for existing CloudAcount with id ID under configDir
     */
    this.id = ID;
    this.config = new Properties();
    this.configTemplate = templates.get(this.type);

    // Define path of account configuration file
    Path configPath = Paths.get(configDir, "acct" + Integer.toString(this.id) + ".properties");
    String configFilename = configPath.toString();
    this.configFile = new File(configFilename);

    // set account type
    boolean accountFound = setProperties();
    if (accountFound) {
      this.type = AccountType.valueOf(this.config.getProperty("configType"));
    }

  }
  
  public boolean configsSet() {
    /**
     * Returns True if configuration is set correctly for this account
     */

    boolean fieldsValid = false;
    boolean configFileExists = false;

    try {
      if (this.configFile.exists()) {

        configFileExists = true;

        InputStream inStream = new FileInputStream(this.configFile);
        this.config.load(inStream);
        fieldsValid = checkAccountProperties(this.config);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return configFileExists && fieldsValid;

  }
  
  public void setConfiguration() {
    /**
     * Sets config field from configFile
     */

    if (!configsSet()) {

      // Create configuration file is it doesn't exist & copy template to it
      try {
        if (this.configFile.createNewFile()) {
          InputStream inStream = new FileInputStream(this.configTemplate);
          Properties temp = new Properties();
          temp.load(inStream);

          FileOutputStream outStream = new FileOutputStream(this.configFile);
          temp.store(outStream, "Properties for box connection account " + Integer.toString(this.id));
          outStream.close();

          System.out.println("Account configuration file has been created with a template: " + this.configFile.getName()
              + ". \nPlease fill out.");

        }
      } catch (FileNotFoundException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      System.out.println("Configuration for acct" + this.id + " has been set and validated.");
    }
  }

  private boolean checkAccountProperties(Properties props) {
    /**
     * Confirms if fields in props are valid for an account.
     */

    boolean fieldsOK = true;

    switch (type) {

    case BOX:
      // check if private key file exists
      // TODO: be more robust here
      String privateKeyFilename = props.getProperty("privateKeyFile");
      File privateKeyFile = new File(privateKeyFilename);
      fieldsOK = privateKeyFile.exists();

      for (Object value : props.values()) {
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

  public int getID() {
    return this.id;
  }

  public AccountType getType() {
    return this.type;
  }

  public Properties getConfiguration() {
    return this.config;
  }

  public String getPollToken() {
    return this.config.getProperty("pollToken");
  }

}
