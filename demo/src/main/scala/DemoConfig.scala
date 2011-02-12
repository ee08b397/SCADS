package edu.berkeley.cs
package radlab
package demo

import avro.marker._
import avro.runtime._

import scads.perf.ExperimentalScadsCluster

import scads.comm._
import scads.storage.ScalaEngineTask
import deploylib.mesos._
import deploylib.ec2._

import net.lag.logging.Logger

import java.io.File

object DemoConfig {
  protected val logger = Logger()
  val zone = "us-east-1a"
  val appServerCapacity = 10 /* req/sec */
  
  def twitterSpamRoot = zooKeeperRoot.getOrCreate("twitterSpam")

  val javaExecutorPath = "/usr/local/mesos/frameworks/deploylib/java_executor"
  def localMesosMasterPid = "1@" + java.net.InetAddress.getLocalHost.getHostName + ":5050"

  //TODO: Add other ZooKeeper
  val zooKeeperRoot = ZooKeeperNode("zk://ec2-50-16-2-36.compute-1.amazonaws.com,ec2-174-129-105-138.compute-1.amazonaws.com/demo")

  val mesosMasterNode = zooKeeperRoot.getOrCreate("mesosMaster")
  def mesosMaster = new String(mesosMasterNode.data)

  def serviceSchedulerNode = zooKeeperRoot.getOrCreate("serviceScheduler")
  def serviceScheduler = classOf[RemoteActor].newInstance.parse(serviceSchedulerNode.data)

  /* SCADr */
  def scadrRoot =  zooKeeperRoot.getOrCreate("apps/scadr")
  def scadrWebServerList = scadrRoot.getOrCreate("webServerList")
  val scadrWarFile = new File("piql/scadr/src/main/rails/rails.war")
  def scadrWar =
    if(scadrWarFile.exists)
      S3CachedJar(S3Cache.getCacheUrl(scadrWarFile))
    else {
      logger.info("Using cached scadr war file.")
      S3CachedJar("http://s3.amazonaws.com/deploylibCache-marmbrus/3a7c8abd9da8ba27e4bd822135179a6b")
    }

  /* gRADit */
  def graditRoot =  zooKeeperRoot.getOrCreate("apps/gradit")
  def graditWebServerList = graditRoot.getOrCreate("webServerList")
  val graditWarFile = new File("piql/gradit/src/main/rails/rails.war")
  def graditWar =
    if(graditWarFile.exists)
      S3CachedJar(S3Cache.getCacheUrl(graditWarFile))
    else {
      logger.info("Using cached gradit war file.")
      S3CachedJar("http://s3.amazonaws.com/deploylibCache-marmbrus/5a65ddddab94db7bfa7cdf5e9914c47c")
    }

  val jdbcDriver = classOf[com.mysql.jdbc.Driver]
  val dashboardDb = "jdbc:mysql://dev-mini-demosql.cwppbyvyquau.us-east-1.rds.amazonaws.com:3306/radlabmetrics?user=radlab_dev&password=randyAndDavelab"

  def rainJars = {
    val rainLocation  = new File("../rain-workload-toolkit")
    val workLoadDir = new File(rainLocation, "workloads")
    val rainJar = new File(rainLocation, "rain.jar")
    val scadrJar = new File(workLoadDir, "scadr.jar")

    if(rainJar.exists && scadrJar.exists) {
      logger.info("Using local jars")
      S3CachedJar(S3Cache.getCacheUrl(rainJar.getCanonicalPath)) ::
      S3CachedJar(S3Cache.getCacheUrl(scadrJar.getCanonicalPath)) :: Nil
    }
    else {
      logger.info("Using cached S3 jars")
      S3CachedJar("http://s3.amazonaws.com/deploylibCache-rean/f2f74da753d224836fedfd56c496c50a") ::
      S3CachedJar("http://s3.amazonaws.com/deploylibCache-rean/3971dfa23416db1b74d47af9b9d3301d") :: Nil
    }
  }

  implicit def classSource = MesosEC2.classSource

  protected def toServerList(node: ZooKeeperProxy#ZooKeeperNode) = {
    val servers = new String(scadrWebServerList.data).split("\n")
    servers.zipWithIndex.map {
      case (s: String, i: Int) => <a href={"http://%s:8080/".format(s)} target="_blank">{i}</a>
    }
  }

  def toHtml: scala.xml.NodeSeq = {
    <div>RADLab Demo Setup: <a href={"http://" + serviceScheduler.host + ":8080"} target="_blank">Mesos Master</a><br/> 
      Scadr Servers: {toServerList(scadrWebServerList)}
    </div>
  }
  
  /**
  e.g val namespaces = Map("users" -> classOf[edu.berkeley.cs.scads.piql.scadr.User],
	       "thoughts" -> classOf[edu.berkeley.cs.scads.piql.scadr.Thought],
	       "subscriptions" -> classOf[edu.berkeley.cs.scads.piql.scadr.Subscription])
  */
  def initScadrCluster(clusterAddress:String):Unit = {
    val clusterRoot = ZooKeeperNode(clusterAddress)
    val cluster = new ExperimentalScadsCluster(clusterRoot)
    
    logger.info("Adding servers to cluster for each namespace")
    val namespaces = Map("users" -> classOf[edu.berkeley.cs.scads.piql.scadr.User],
  	       "thoughts" -> classOf[edu.berkeley.cs.scads.piql.scadr.Thought],
  	       "subscriptions" -> classOf[edu.berkeley.cs.scads.piql.scadr.Subscription])
    serviceScheduler !? RunExperimentRequest(namespaces.keys.toList.map(key => ScalaEngineTask(clusterAddress = cluster.root.canonicalAddress, name = Option(key + "node0")).toJvmTask ))
    
    cluster.blockUntilReady(namespaces.size)
    logger.info("Creating the namespaces")
    namespaces.foreach {
      case (name, entityType) => {
	      logger.info("Creating namespace %s", name)
	      val entity = entityType.newInstance
	      val (keySchema, valueSchema) = (entity.key.getSchema, entity.value.getSchema) //entity match {case e:AvroPair => (e.key.getSchema, e.value.getSchema) }
	      val initialPartitions = (None, cluster.getAvailableServers(name)) :: Nil
	      cluster.createNamespace(name, keySchema, valueSchema, initialPartitions)
      }
    }
    startScadrDirector()
  }

  def initGraditCluster(clusterAddress:String):Unit = {
    val clusterRoot = ZooKeeperNode(clusterAddress)
    val cluster = new ExperimentalScadsCluster(clusterRoot)

    logger.info("Adding servers to cluster for each namespace")
    val namespaces = Map("words" -> classOf[edu.berkeley.cs.scads.piql.gradit.Word],
  	       "books" -> classOf[edu.berkeley.cs.scads.piql.gradit.Book],
  	       "wordcontexts" -> classOf[edu.berkeley.cs.scads.piql.gradit.WordContext],
  	       "wordlists" -> classOf[edu.berkeley.cs.scads.piql.gradit.WordList],
  	       "games" -> classOf[edu.berkeley.cs.scads.piql.gradit.Game],
  	       "gameplayers" -> classOf[edu.berkeley.cs.scads.piql.gradit.GamePlayer],
  	       "users" -> classOf[edu.berkeley.cs.scads.piql.gradit.User])
    serviceScheduler !? RunExperimentRequest(namespaces.keys.toList.map(key => ScalaEngineTask(clusterAddress = cluster.root.canonicalAddress, name = Option(key + "node0")).toJvmTask ))

    cluster.blockUntilReady(namespaces.size)
    logger.info("Creating the namespaces")
    namespaces.foreach {
      case (name, entityType) => {
	      logger.info("Creating namespace %s", name)
	      val entity = entityType.newInstance
	      val (keySchema, valueSchema) = (entity.key.getSchema, entity.value.getSchema)
	      val initialPartitions = (None, cluster.getAvailableServers(name)) :: Nil
	      cluster.createNamespace(name, keySchema, valueSchema, initialPartitions)
      }
    }
    startGraditDirector()
  }
}