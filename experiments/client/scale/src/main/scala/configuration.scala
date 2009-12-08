package scaletest

import deploylib._
import deploylib.config._
import deploylib.runit._
import deploylib.xresults._
import edu.berkeley.cs.scads.thrift._
import scala.collection.jcl.Conversions._

import java.io.File

case class ScadsDeployment(zooService: RunitService, zooUri: String, storageServices: List[RunitService])

object ScadsDeployment extends ConfigurationActions {
	val storageEnginePort = 9090
	val zookeeperPort = 2181
	val allKeys = RangedPolicy.convert((null, null)).get(0)

	def deployScadsCluster(nodes: List[RunitManager], bulkLoad: Boolean): ScadsDeployment = {
		logger.info("Configuring zookeeper")
		val zooNode = nodes(0)
		val zooService = ScadsDeployment.deployZooKeeperServer(zooNode)
		zooService.watchFailures
		zooService.start
		zooService.blockTillUpFor(5)
		zooNode.blockTillPortOpen(ScadsDeployment.zookeeperPort)

		logger.info("Configuring storage engines")
		val storageServices = nodes.map(ScadsDeployment.deployStorageEngine(_, zooNode, bulkLoad))
		storageServices.foreach(_.watchFailures)
		storageServices.foreach(_.start)
		storageServices.foreach(_.blockTillUpFor(5))
		nodes.foreach(_.blockTillPortOpen(ScadsDeployment.storageEnginePort))

		ScadsDeployment(zooService, zooNode.hostname + ":" + zookeeperPort, storageServices)
	}

	def deployStorageEngine(target: RunitManager, zooServer: RemoteMachine, bulkLoad: Boolean): RunitService = {
    val bulkLoadFlag = if(bulkLoad) " -b " else ""
		createJavaService(target, new File("target/scale-1.0-SNAPSHOT-jar-with-dependencies.jar"),
			"edu.berkeley.cs.scads.storage.JavaEngine",
      2048,
			"-p " + storageEnginePort + " -z " + zooServer.hostname + ":" + zookeeperPort + bulkLoadFlag)
	}

	def deployZooKeeperServer(target: RunitManager): RunitService = {
		val zooStorageDir = createDirectory(target, new File(target.rootDirectory, "zookeeperdata"))
		val zooService = createJavaService(target, new File("target/scale-1.0-SNAPSHOT-jar-with-dependencies.jar"),
			"org.apache.zookeeper.server.quorum.QuorumPeerMain",
      1024,
			"zoo.cnf")
		val config = <configuration type="zookeeper">
									<tickTime>10000</tickTime>
									<initLimit>10</initLimit>
									<syncLimit>5</syncLimit>
									<clientPort>{zookeeperPort}</clientPort>
									<dataDir>{zooStorageDir}</dataDir>
								</configuration>

		val zooConfigData = config.descendant.filter(_.getClass == classOf[scala.xml.Elem]).map(e => e.label + "=" + e.text).mkString("", "\n", "\n")
		val zooConfigFile = createFile(target, new File(zooService.serviceDir, "zoo.cnf"), zooConfigData, "644")

		XResult.storeXml(config)

		return zooService
	}

	def captureEngineState(target: RemoteMachine): Unit = {
		val node = new StorageNode(target.hostname, storageEnginePort)
		val namespaces =
			node.useConnection(c => c.get_set("resp_policies", allKeys).map(r => {

				<namespace name={r.key}>
					{
						RangedPolicy.convert(c.get_responsibility_policy(r.key)).map(p => {
							<responsibilityPolicy>
								<startKey>{p._1}</startKey>
								<endKey>{p._2}</endKey>
							</responsibilityPolicy>

						})
					}
					<keyCount>{node.useConnection(_.count_set(r.key, allKeys))}</keyCount>
				</namespace>
			}))

		XResult.storeXml(
			<storageEngine>
				<host>{target.hostname}</host>
				{namespaces}
			</storageEngine>
		)
	}
}