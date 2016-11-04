# cloudpolling
A umd-libraries project to poll events from multiple cloud storage sites and sync with local storage.

**SET-UP:**
* Clone project & run 'mvn install' within cloudpolling folder.
* Set $CPOLL_CONFIGS environment variable as directory where you'd like to store configuration files for your cloud accounts.

**USAGE:**

*mvn exec:java -Dexec.args="ARGUMENTS"*

**ARGUMENT OPTIONS:**

* new [projectname] : creates a new polling project  
* add [projectname] [acct_type] : adds a cloud account to a project (types: Box, DropBox, Drive)  
* poll [projectname] : polls all accounts in a project and syncs account folder with local system  


**PROJECT STATUS:**  

Currently, the app can only sync local file system & a Box account. DropBox, Drive, and Solr capabilities coming soon.
