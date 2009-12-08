import scaletest._
import deploylib._
import deploylib.rcluster._
import deploylib.xresults._
import edu.berkeley.cs.scads.thrift._
import org.apache.log4j.Logger
import deploylib.Util
import java.io.File

settings.maxPrintString = 1000000

class LoadExp(threads: Int, bulkLoad: Boolean, testSize: Int) {
	val logger = Logger.getLogger("script")

	val nodes = List(r27, r10, r9, r32)
	val partitions = IntTestDeployment.createPartitions(testSize, nodes.size)

	XResult.startExperiment("Threaded Load Experiment: " + threads + " " + bulkLoad + " " + testSize)

	logger.info("Cleaning up")
	nodes.foreach(_.clearAll)
	nodes.foreach(_.executeCommand("killall java"))
	nodes.foreach(_.setupRunit)
	nodes.foreach(_.stopWatches)

	val cluster = ScadsDeployment.deployScadsCluster(nodes, bulkLoad)

	val loadServices = partitions.zip(nodes).map(p => {
		logger.info("Setting range policy for: " + p)
		val n = StorageNode(p._2.hostname, ScadsDeployment.storageEnginePort)
		Util.retry(5)(() => {
			n.useConnection(_.set_responsibility_policy("intKeys", RangedPolicy.convert((p._1.start, p._1.end))))
		})
		IntTestDeployment.deployLoadClient(p._2, "ThreadedLoader", cluster.zooUri + " " + p._1.start + " " + p._1.end + " " + threads)
	})
	loadServices.foreach(_.watchFailures)
	loadServices.foreach(_.once)

	val postTestCollection = Future {
		loadServices.foreach(_.blockTillDown)
		logger.info("Begining Post-test collection")
		nodes.foreach(n => ScadsDeployment.captureEngineState(n))
		(cluster.storageServices ++ loadServices).foreach(_.captureLog)
		cluster.storageServices.foreach(s => XResult.captureDirectory(s.manager, new File(s.serviceDir, "db")))
		logger.info("Post test collection complete")
	}
}