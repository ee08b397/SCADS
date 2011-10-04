package edu.berkeley.cs.scads.perf

import deploylib.ec2._
import deploylib.mesos._
import edu.berkeley.cs.scads.comm._
import edu.berkeley.cs.scads.config._
import edu.berkeley.cs.scads.storage._
import edu.berkeley.cs.avro.runtime._
import edu.berkeley.cs.avro.marker._

import org.apache.zookeeper.CreateMode

import java.io.File
import net.lag.logging.Logger

class ExperimentalScadsCluster(root: ZooKeeperProxy#ZooKeeperNode) extends ScadsCluster(root) {
  def blockUntilReady(clusterSize: Int): Unit = {
    while(getAvailableServers.size < clusterSize) {
      logger.info("Waiting for cluster to start " + cluster.getAvailableServers.size + " of " + clusterSize + " ready.")
      Thread.sleep(1000)
    }
  }
}

trait ExperimentBase {
  var resultClusterAddress = Config.config.getString("scads.perf.resultZooKeeperAddress").getOrElse(sys.error("need to specify scads.perf.resultZooKeeperAddress")) + "home/" + System.getenv("USER") + "/deploylib/"
  val resultCluster = new ScadsCluster(ZooKeeperNode(resultClusterAddress))

  implicit def productSeqToExcel(lines: Seq[Product]) = new {
    import java.io._
    def toExcel: Unit = {
      val file = File.createTempFile("scadsOut", ".csv")
      val writer = new FileWriter(file)

      lines.map(_.productIterator.mkString(",") + "\n").foreach(writer.write)
      writer.close

      Runtime.getRuntime.exec(Array("/usr/bin/open", file.getCanonicalPath))
    }
  }
}

trait TaskBase {
  def newScadsCluster(size: Int)(implicit cluster: Cluster, classSource: Seq[ClassSource]): ScadsCluster = {
    val clusterRoot = cluster.zooKeeperRoot.getOrCreate("scads").createChild("experimentCluster", mode = CreateMode.PERSISTENT_SEQUENTIAL)
    val serverProcs = Array.fill(size)(ScalaEngineTask(clusterAddress=clusterRoot.canonicalAddress).toJvmTask)

    cluster.serviceScheduler.scheduleExperiment(serverProcs)
    new ScadsCluster(clusterRoot)
  }

  def newMDCCScadsCluster(size: Int, cluster1: Cluster, cluster2: Cluster): ScadsCluster = {
    val clusterRoot = cluster1.zooKeeperRoot.getOrCreate("scads").createChild("experimentCluster", mode = CreateMode.PERSISTENT_SEQUENTIAL)

    val serverProcs1 = Array.fill(size)(ScalaEngineTask(clusterAddress=clusterRoot.canonicalAddress).toJvmTask(cluster1.classSource))
    val serverProcs2 = Array.fill(size)(ScalaEngineTask(clusterAddress=clusterRoot.canonicalAddress).toJvmTask(cluster2.classSource))

    (serverProcs1 ++ serverProcs2).foreach(_.props += "scads.comm.externalip" -> "true")

    cluster1.serviceScheduler.scheduleExperiment(serverProcs1)
    cluster2.serviceScheduler.scheduleExperiment(serverProcs2)

    new ScadsCluster(clusterRoot)
  }
}
