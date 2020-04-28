package io.cloudstate.proxy.spanner

import io.cloudstate.proxy.CloudStateProxyMain

object CloudstateSpannerProxyMain {

  def main(args: Array[String]): Unit = {
    val actorSystem = CloudStateProxyMain.start()
  }
}
