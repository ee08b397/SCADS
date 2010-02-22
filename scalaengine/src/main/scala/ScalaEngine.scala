package edu.berkeley.cs.scads.storage

import com.sleepycat.je.Environment
import com.sleepycat.je.EnvironmentConfig
import com.sleepycat.je.jmx.JEMonitor

import edu.berkeley.cs.scads.comm._
import org.apache.log4j.Logger

object ScalaEngine extends optional.Application {
	private val logger = Logger.getLogger("ScalaEngine")
	def main(port: Int, zooKeeper: String, dbDir: Option[java.io.File], cachePercentage: Option[Int], verbose: Boolean): StorageHandler = {
		val config = new EnvironmentConfig()
		config.setAllowCreate(true)
		config.setTransactional(true)
		config.setCachePercent(cachePercentage.getOrElse(80))

		val dir = dbDir.getOrElse(new java.io.File("db"))
		if(!dir.exists()) {
			dir.mkdir
		}

		val env = new Environment(dir, config)
		logger.info("Connecting to zookeeper")
		val zooRoot = new ZooKeeperProxy(zooKeeper).root("scads")
		val handler = new StorageHandler(env, zooRoot)
		logger.info("Starting listener")
    	MessageHandler.registerService("Storage",handler)
		MessageHandler.startListener(port)
		
		return handler
	}
}