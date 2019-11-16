/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.util


import java.lang.reflect.InvocationTargetException

import org.apache.samza.config._
import org.apache.samza.SamzaException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Random

import org.apache.samza.util.ScalaJavaUtil.JavaOptionals

import scala.collection.JavaConverters._


object Util extends Logging {
  val FALLBACK_VERSION = "0.0.1"

  /**
   * Make an environment variable string safe to pass.
   */
  def envVarEscape(str: String) = str.replace("\"", "\\\"").replace("'", "\\'")

  def getSamzaVersion(): String = {
    Option(this.getClass.getPackage.getImplementationVersion)
      .getOrElse({
        warn("Unable to find implementation samza version in jar's meta info. Defaulting to %s" format FALLBACK_VERSION)
        FALLBACK_VERSION
      })
  }

  def getTaskClassVersion(config: Config): String = {
    try {
      val appClass = Option(new ApplicationConfig(config).getAppClass)
      if (appClass.isDefined) {
        Option.apply(Class.forName(appClass.get).getPackage.getImplementationVersion).getOrElse(FALLBACK_VERSION)
      } else {
        val taskClass = new TaskConfig(config).getTaskClass
        if (taskClass.isPresent) {
          Option.apply(Class.forName(taskClass.get()).getPackage.getImplementationVersion).getOrElse(FALLBACK_VERSION)
        } else {
          warn("Unable to find app class or task class. Defaulting to %s" format FALLBACK_VERSION)
          FALLBACK_VERSION
        }
      }
    } catch {
      case e: Exception => {
        warn("Unable to find implementation version in jar's meta info. Defaulting to %s" format FALLBACK_VERSION)
        FALLBACK_VERSION
      }
    }
  }

  def getLocalHost: InetAddress = {
    doGetLocalHost(new NetworkingUtil)
  }

  /**
   * Returns the the first host address which is not the loopback address, or [[java.net.InetAddress#getLocalHost]] as a fallback
   *
   * @return the [[java.net.InetAddress]] which represents the localhost
   */
  def doGetLocalHost(networkingUtil: NetworkingUtil): InetAddress = {
    val localHost = networkingUtil.inetAddressGetLocalHost
    if (localHost.isLoopbackAddress) {
      debug("Hostname %s resolves to a loopback address, trying to resolve an external IP address.".format(localHost.getHostName))
      val networkInterfaces = if (System.getProperty("os.name").startsWith("Windows")) {
        networkingUtil.networkInterfaceGetNetworkInterfaces.asScala.toList
      } else {
        networkingUtil.networkInterfaceGetNetworkInterfaces.asScala.toList.reverse
      }
      for (networkInterface <- networkInterfaces) {
        val addresses = networkingUtil.networkInterfaceGetInetAddresses(networkInterface).asScala.toList
          .filterNot(address => address.isLinkLocalAddress || address.isLoopbackAddress)
        if (addresses.nonEmpty) {
          val address = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(addresses.head)
          debug("Found an external IP address %s which represents the localhost.".format(networkingUtil.inetAddressGetHostAddress(address)))
          return networkingUtil.inetAddressGetByAddress(networkingUtil.inetAddressGetAddress(address))
        }
      }
    }
    localHost
  }

  /**
   * Re-writes configuration using a ConfigRewriter, if one is defined. If
   * there is no ConfigRewriter defined for the job, then this method is a
   * no-op.
   *
   * @param config The config to re-write
   * @return re-written config
   */
  def rewriteConfig(config: Config): Config = {
    JavaOptionals.toRichOptional(new JobConfig(config).getConfigRewriters).toOption match {
      case Some(rewriters) => rewriters.split(",").foldLeft(config)(applyRewriter(_, _))
      case _ => config
    }
  }

  /**
    * Re-writes configuration using a ConfigRewriter, defined with the given rewriterName in config.
    * @param config the config to re-write
    * @param rewriterName the name of the rewriter to apply
    * @return the rewritten config
    */
  def applyRewriter(config: Config, rewriterName: String): Config = {
    val rewriterClassName = JavaOptionals.toRichOptional(new JobConfig(config).getConfigRewriterClass(rewriterName))
      .toOption
      .getOrElse(throw new SamzaException("Unable to find class config for config rewriter %s." format rewriterName))
    val rewriter = ReflectionUtil.getObj(rewriterClassName, classOf[ConfigRewriter])
    info("Re-writing config with " + rewriter)
    rewriter.rewrite(rewriterName, config)
  }

}

/**
 * Do this so Powermockito can mock the system classes.
 * Powermockito doesn't seem to work as well with Scala singletons.
 * In Java, it seems like it will work to use Powermock without this wrapper.
 */
class NetworkingUtil {
  def inetAddressGetLocalHost: InetAddress = {
    InetAddress.getLocalHost
  }

  def inetAddressGetByAddress(address: Array[Byte]): InetAddress = {
    InetAddress.getByAddress(address)
  }

  def inetAddressGetHostAddress(inetAddress: InetAddress): String = {
    inetAddress.getHostAddress
  }

  def inetAddressGetAddress(inetAddress: InetAddress): Array[Byte] = {
    inetAddress.getAddress
  }

  def networkInterfaceGetNetworkInterfaces: java.util.Enumeration[NetworkInterface] = {
    NetworkInterface.getNetworkInterfaces
  }

  def networkInterfaceGetInetAddresses(networkInterface: NetworkInterface): java.util.Enumeration[InetAddress] = {
    networkInterface.getInetAddresses
  }
}