package edu.umd.lib.cloudpolling;

import java.util.Properties;

public class CloudConnector implements java.io.Serializable {

  private static final long serialVersionUID = 3305450132714018775L;
  /**
   * Generalization of BoxConnector, DropBoxConnector, and Drive Connector.
   */

  public static Properties config;

  public CloudConnector() {
  }

  public CloudConnector(Properties acctConfig) {
    config = acctConfig;
  }

  public static String getType() {
    return config.get("configType").toString();
  }

}
