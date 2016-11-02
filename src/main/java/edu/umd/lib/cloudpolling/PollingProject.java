package edu.umd.lib.cloudpolling;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;

import edu.umd.lib.cloudpolling.CloudAccount.AccountType;

public class PollingProject {

  /**
   * Class that hold all configuration information for a polling project and its
   * associated accounts
   */

  public static final String CONFIG_TEMPLATE_NAME = "resources/templates/project.properties";

  public String name; // name of project
  public Properties config; // configuration of project
  public File configFile; // file that holds project's user-defined configuration info
  public File projectDir; // folder of project's config files
  public File accountsDir; // folder of project's accounts' config files

  public PollingProject(String projectName, String configDir) {
    /**
     * Initializes instance variables
     */

    this.name = projectName;
    this.config = new Properties();

    Path projectPath = Paths.get(configDir, this.name);
    String projectDirName = projectPath.toString();
    this.projectDir = new File(projectDirName);

    Path configPath = Paths.get(projectDirName, name + ".properties");
    String configFileName = configPath.toString();
    this.configFile = new File(configFileName);

    Path accountsPath = Paths.get(projectPath.toString(), "accts");
    String accountsDirName = accountsPath.toString();
    this.accountsDir = new File(accountsDirName);

  }

  public boolean configsSet() {
    /**
     * Returns True if configuration is set correctly for this project
     */

    boolean fieldsValid = false;
    boolean configFileExists = false;

    try {
      if (this.configFile.exists()) {

        configFileExists = true;

        InputStream inStream = new FileInputStream(this.configFile);
        this.config.load(inStream);
        fieldsValid = checkProjectProperties(this.config);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }

    return configFileExists && fieldsValid;

  }

  public void setConfiguration() {
    /**
     * Sets config fields from configFile, building configuration file structure if necessary
     */

    if (!configsSet()) {

      // Create project configuration directory if it doesn't exist
      if (!this.projectDir.exists()) {
        this.projectDir.mkdir();
      }

      // Create project's accounts configuration directory if it doesn't exist
      if (!accountsDir.exists()) {
        accountsDir.mkdir();
      }

      // Create configuration file is it doesn't exist & copy template to it
      try {
        if (this.configFile.createNewFile()) {
          File template = new File(CONFIG_TEMPLATE_NAME);
          InputStream inStream = new FileInputStream(template);
          Properties temp = new Properties();
          temp.load(inStream);

          FileOutputStream outStream = new FileOutputStream(this.configFile);
          temp.store(outStream, "Properties for polling project " + name);
          outStream.close();

          System.out.println("Project configuration file has been created with a template: " + this.configFile.getName()
              + ". \nPlease fill out.");

        }
      } catch (FileNotFoundException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      System.out.println("Configuration for project " + this.name + " has been set and validated.");
    }

  }

  public void addAccount(AccountType accountType) {
    /**
     * Creates a new CloudAccount configuration file within accountsDir.  
     */
    
    // Define ID for new account
    List<int> ids = getAccountIds();
    int newID = Collections.max(ids) + 1;

    // Create new cloud account object & set its configuration
    CloudAccount account = new CloudAccount(newID, accountType, this.acctsFolderName);
    account.setConfiguration();

  }

  private Boolean checkProjectProperties(Properties props) {
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

  public String getName() {
    return this.name;
  }
  
  public List<Integer> getAccountIds() {
    /**
     * Gets all account id's from accountsDir
     */
    
    ArrayList<Integer>ids = new ArrayList<Integer>();
    String filename = null;
    
    File[] files = this.accountsDir.listFiles();
    
    for (File file : files){
      
      filename = file.getName();

      if (filename.endsWith(".properties")){
        ids.add(Integer.parseInt(filename.split(".")[0].split("acct")[1]));
      }
    }

    return ids;
  }
  
  public String getSyncFolder() {
    return this.config.getProperty("syncFolder");
  }
  
  publicString getAccountsDirName() {
    return this.accountsDir.getName();
  }

}
