package edu.berkeley.cs
package radlab

package object demo {
  import DemoConfig._
  import scads.comm._
  import deploylib.mesos._

  def fixServiceSchedulerPid: Unit = {
    DemoConfig.serviceSchedulerNode.data = RemoteActor(MesosEC2.master.publicDnsName, 9000, ActorNumber(0)).toBytes
  }

  def setMesosMaster: Unit = {
    DemoZooKeeper.root.getOrCreate("demo/mesosMaster").data = MesosEC2.clusterUrl.getBytes
  }

  def startScadr: Unit = {
    serviceScheduler !? RunExperimentRequest(
      JvmMainTask(MesosEC2.classSource,
		  "edu.berkeley.cs.radlab.demo.WebAppScheduler",
		  "--name" :: "SCADr" ::
		  "--mesosMaster" :: mesosMaster ::
		  "--executor" :: javaExecutorPath ::
		  "--warFile" :: scadrWar :: Nil) :: Nil
    )
  }
}