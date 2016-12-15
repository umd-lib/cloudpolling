# cloudpolling
This application is a prototype developed as a part of a data management and preservation research project with the University of Maryland Libraries. The app is used to sync disparate folders stored on disparate cloud storage sites (including Box, DropBox, and GoogleDrive) with a single local storage location and index the content using Apache Solr. It can manage multiple 'polling projects', providing the ability to sync different groups of cloud accounts separately. Each time you poll a project, the application communicates with the project's associated cloud accounts and polls any changes that have occurred since that previous polling interval, reflecting those changes in your local system. Please note that this application is not fully functional, but is an ongoing project. 

**SET-UP:**
* Clone project & run 'mvn install' within cloudpolling folder.
* Set $CPOLL_CONFIGS environment variable as a directory where you prefer to store configuration files for your polling projects.

**USAGE:**
*mvn exec:java -Dexec.args="ARGUMENTS"*

**ARGUMENT OPTIONS:**
* new [projectname] : creates a new polling project  
* add [projectname] [acct_type] : adds a cloud account to a project (types: Box, DropBox, Drive)  
* poll [projectname] : polls all accounts in a project and syncs account folder with local system  
* reset [projectname] : resets all polling tokens for a project (on next poll, app will simply download all files from associated cloud account)
* boxappuser [projectname] [acct_name]: creates a Box app user using the information in specified account's configuration file


**API APPLICATIONS:**  
Each of the supported cloud storage sites come with their own set of instructions for obtaining an API key and authenticating a connection to a user account. Please see their developers websites. After setting up an API application, use information found on your developer's console and the user's account to configure your cloud account by filling neccessary fields in its .properties file. 
* Box : https://developer.box.com/
* DropBox: https://www.dropbox.com/developers
* GoogleDrive: https://console.developers.google.com/flows/enableapi?apiid=drive&authuser=3

**MORE INFO on usage, implementation logic, & project status for UMD members**
https://docs.google.com/a/umd.edu/document/d/1Gph5Vh9UqrkIzwnVVPQrLiucOEjirqOF5927EFPv0lw/edit?usp=sharing

For more infomation, please contact Tara at taralarrue@gmail.com

