package io.cloudstate.proxy.jdbc

import io.cloudstate.proxy.CloudStateProxyMain

object CloudStateJdbcProxyMain {

  def main(args: Array[String]): Unit = {

    val actorSystem = CloudStateProxyMain.start()

    // If in dev mode, we want to ensure the tables get created, which they won't be unless the health check is
    // instantiated
    val config = new CloudStateProxyMain.Configuration(actorSystem.settings.config.getConfig("cloudstate.proxy"))
    if (config.devMode) {
      new SlickEnsureTablesExistReadyCheck(actorSystem)
    }
  }

}
