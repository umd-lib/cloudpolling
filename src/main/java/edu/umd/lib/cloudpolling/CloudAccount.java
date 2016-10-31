package edu.umd.lib.cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Properties;

public class CloudAccount implements java.io.Serializable {

  /**
   * Class that holds configuration (includes authentication info for an API
   * connection) info for a cloud service account.
   *
   * Account Types handled : Box, DropBox (soon), GoogleDrive (soon)
   */
  public static enum AccountType {
    BOX, DROPBOX, GOOGLEDRIVE
  }

  private static final long serialVersionUID = -6137244347382437093L;

  // TODO: add dropbox & google drive config templates
  public static HashMap<AccountType, File> templates;
  static {
    HashMap<AccountType, File> map = new HashMap<AccountType, File>();

    File boxTemplate = new File("resources/templates/box.properties");
    map.put(AccountType.BOX, boxTemplate);

    templates = map;
  }

  public int id; // ID of this account (auto-generated)
  public AccountType type; // type of this account
  public File configTemplate; // template file account configuration
  public Properties config; // configuration fields for this account
  public String pollToken; // last polling position

  public CloudAccount(int newID, AccountType acctType) {
    /**
     * Initialize instance variables
     */

    this.id = newID;
    this.type = acctType;
    this.config = new Properties();
    this.configTemplate = templates.get(this.type);
    this.pollToken = null;
  }

  public boolean setProperties(String topDir) {
    /**
     * Reads or creates configuration file for the account. Returns True if
     * properties fields can be read and are valid. Returns False if file exists
     * or has been created, but properties fields are not yet valid
     */

    boolean propertiesDefined = false;

    // Define path of account configuration file
    Path configPath = Paths.get(topDir, "acct" + Integer.toString(id) + ".properties");
    String configFilename = configPath.toString();
    File configFile = new File(configFilename);

    try {
      if (configFile.createNewFile()) {
        // If file does NOT exist, create it, copy template to it, but return
        // false (properties not defined for this account)

        Properties temp = new Properties();
        InputStream inputStream = new FileInputStream(this.configTemplate);
        temp.load(inputStream);
        temp.put("configID", Integer.toString(this.id));

        FileOutputStream fileOut = new FileOutputStream(configFile);
        temp.store(fileOut, "Properties for box connection account " + Integer.toString(id));
        fileOut.close();

        System.out.println("File is created: " + configFilename + ". \nPlease fill out and rerun 'add' command.");

      } else {

        // If file exists, read properties
        InputStream inputStream = new FileInputStream(configFile);
        this.config.load(inputStream);

        // save config field & check that property fields are valid.
        propertiesDefined = checkProperties(this.config);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return propertiesDefined;
  }

  private Boolean checkProperties(Properties props) {
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
    return this.pollToken;
  }

}
