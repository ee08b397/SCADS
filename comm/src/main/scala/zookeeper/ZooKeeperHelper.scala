package edu.berkeley.cs.scads.comm

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.SyncVar
import edu.berkeley.cs.scads.config._

import org.apache.zookeeper.server._
import org.apache.zookeeper.CreateMode
import persistence._

import java.net.InetSocketAddress

import net.lag.logging.Logger

/**
 * Helper object for spinning up a local zookeeper instance.  Used primarily for testing.
 */
object ZooKeeperHelper {
  private val logger = Logger()

  private val currentPort = new AtomicInteger(Config.config.getInt("scads.test.zkport", 2000)) // start at port 2000

  //HACK because zookeper is anoyingly noisy in the logs
  org.apache.log4j.Logger.getRootLogger.setLevel(org.apache.log4j.Level.WARN)

  lazy val testZooKeeper = createTestZooKeeper()

  def getTestZooKeeper() = testZooKeeper.root.createChild("testZooKeeper", mode=CreateMode.PERSISTENT_SEQUENTIAL)

  protected def newTempDir: File = {
    val tempDir = File.createTempFile("scads", "zookeeper")
    tempDir.delete()
    tempDir.mkdir()
    tempDir
  }
  
  /**
   * Create a local zookeeper instance in JVM and return a ZooKeeperProxy for it.  
   * Intended for testing purposes only. Is thread safe. Each separate
   * invocation of getTestZooKeeper creates a NEW zookeeper instance
   */
   def createTestZooKeeper(workingDir: File = newTempDir): ZooKeeperProxy = {
    val serverPort = new SyncVar[Int]

    val zooThread = new Thread("Local ZooKeeper") {
      override def run() {
        var continue = true
        while (continue) {
          val tryingPort = currentPort.getAndIncrement()
          logger.info("Trying to start zookeeper instance on port %d", tryingPort)
          try {

            //val config = new ServerConfig
            //config.parse(Array(tryingPort.toString, workingDir.toString))
            //val server = new ZooKeeperServerMain
            //logger.info("calling runFromConfig with config %s", config)
            //server.runFromConfig(config)

            // server.runFromConfig does not give you the correct semantics to check to see if 
            // the server successfully started up, other than polling- the following code below is
            // how runFromConfig is implemented, except we get to place some code after the
            // startup and before the join. 
            // see: http://github.com/apache/zookeeper/blob/release-3.2.1/src/java/main/org/apache/zookeeper/server/NIOServerCnxn.java

            val zkServer = new ZooKeeperServer
            val ftxn = new FileTxnSnapLog(workingDir, workingDir)
            zkServer.setTxnLogFactory(ftxn)
            zkServer.setTickTime(ZooKeeperServer.DEFAULT_TICK_TIME)
            val cnxnFactory = new NIOServerCnxn.Factory(new InetSocketAddress(tryingPort), 0) // no max client connections
            cnxnFactory.startup(zkServer)
            serverPort.set(tryingPort)
            cnxnFactory.join()
            if (zkServer.isRunning) zkServer.shutdown()

          } catch {
            case portInUse: java.net.BindException => 
              logger.warning("Port %d is already in use, trying next port", tryingPort)
            case i: java.lang.InterruptedException =>
              logger.info("Zookeeper dying due to InterrupedException")
              continue = false
            case otherError => 
              logger.critical("Unexpected error when creating test zookeeper: " + otherError + ". Attempting again")
          }
        }
      }
    }
    zooThread.setDaemon(true)
    zooThread.start()

    val successPort = serverPort.get // blocks until server is ready
    val proxy = new ZooKeeperProxy("localhost:" + successPort, timeout=500)
    assert(proxy.root("zookeeper") ne null)
    proxy
  }

}
