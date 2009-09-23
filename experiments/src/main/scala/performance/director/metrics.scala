package scads.director

import java.util.Date

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

import radlab.metricservice._

import org.apache.thrift._
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;

object PerformanceMetrics {
	def load(metricReader:MetricReader, server:String, reqType:String):PerformanceMetrics = {
		// FIX: handling of the dates
		val (date0, workload) = 		metricReader.getSingleMetric(server, "workload", reqType)
		val (date1, latencyMean) = 		metricReader.getSingleMetric(server, "latency_mean", reqType)
		val (date2, latency90p) = 		metricReader.getSingleMetric(server, "latency_90p", reqType)
		val (date3, latency99p) = 		metricReader.getSingleMetric(server, "latency_99p", reqType)
		val (date4, nRequests) = 		metricReader.getSingleMetric(server, "n_requests", reqType)
		val (date5, nSlowerThan50ms) = 	metricReader.getSingleMetric(server, "n_slower_50ms", reqType)
		val (date6, nSlowerThan100ms) = metricReader.getSingleMetric(server, "n_slower_100ms", reqType)
		
		PerformanceMetrics(date0.getTime,metricReader.interval.toInt,workload,latencyMean,latency90p,latency99p, 
			(nRequests/metricReader.report_prob).toInt, (nSlowerThan50ms/metricReader.report_prob).toInt, (nSlowerThan100ms/metricReader.report_prob).toInt)
	}
	
	def estimateFromSamples(samples:List[Double], time:Long, aggregationInterval:Long):PerformanceMetrics = {
		val samplesA = samples.sort(_<_).toArray
		val workload = computeWorkload(samplesA)*1000/aggregationInterval
		val latencyMean = computeMean(samplesA)
		val latency90p = computeQuantileAssumeSorted(samplesA,0.9)
		val latency99p = computeQuantileAssumeSorted(samplesA,0.99)
		val nRequests = samples.size
		val nSlowerThan50ms = samples.filter(_>50).size
		val nSlowerThan100ms = samples.filter(_>100).size
		PerformanceMetrics(time, aggregationInterval, workload, latencyMean, latency90p, latency99p, nRequests, nSlowerThan50ms, nSlowerThan100ms)
	}
	
	private def computeWorkload( data:Array[Double] ): Double = if (data==null||data.size==0) Double.NaN else data.length
	private def computeMean( data:Array[Double] ): Double = if (data==null||data.size==0) Double.NaN else data.reduceLeft(_+_)/data.length
    private def computeQuantile( data:List[Double], q:Double): Double = if (data==null||data.size==0) Double.NaN else data.sort(_<_).toArray( Math.floor(data.length*q).toInt )
    private def computeQuantileAssumeSorted( data:Array[Double], q:Double): Double = if (data==null||data.size==0) Double.NaN else data( Math.floor(data.length*q).toInt )
}
case class PerformanceMetrics(
	val time: Long,
	val aggregationInterval: Long,  // in milliseconds
	val workload: Double,
	val latencyMean: Double,
	val latency90p: Double,
	val latency99p: Double,
	val nRequests: Int,
	val nSlowerThan50ms: Int,
	val nSlowerThan100ms: Int
) {
	override def toString():String = (new Date(time))+" w="+"%.2f".format(workload)+" lMean="+"%.2f".format(latencyMean)+" l90p="+"%.2f".format(latency90p)+" l99p="+"%.2f".format(latency99p)+
									 " all="+nRequests+" >50="+nSlowerThan50ms+" >100ms"+nSlowerThan100ms
	def toShortLatencyString():String = "%.0f".format(latencyMean)+"/"+"%.0f".format(latency90p)+"/"+"%.0f".format(latency99p)
	
	def createMetricUpdates(server:String, requestType:String):List[MetricUpdate] = {
		var metrics = new scala.collection.mutable.ListBuffer[MetricUpdate]()
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"workload"))),workload.toString)
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"latency_mean"))),latencyMean.toString)
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"latency_90p"))),latency90p.toString)
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"latency_99p"))),latency99p.toString)
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"n_requests"))),nRequests.toString)
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"n_slower_50ms"))),nSlowerThan50ms.toString)
		metrics += new MetricUpdate(time,new MetricDescription("scads",s2jMap(Map("server"->server,"request_type"->requestType,"stat"->"n_slower_100ms"))),nSlowerThan100ms.toString)
		metrics.toList
	}
	
	private def s2jMap[K,V](map:Map[K,V]): java.util.HashMap[K,V] = {	
		var jm = new java.util.HashMap[K,V]()
		map.foreach( t => jm.put(t._1,t._2) )
		jm
	}	
}

case class MetricReader(
	val host: String,
	val db: String,
	val interval: Long,
	val report_prob: Double
) {
	val port = 6000
	val user = "root"
	val pass = ""
	
	var connection = Director.connectToDatabase
	initDatabase
	
	def initDatabase() {
        // create database if it doesn't exist and select it
        try {
            val statement = connection.createStatement
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + db)
            statement.executeUpdate("USE " + db)
       	} catch { case ex: SQLException => ex.printStackTrace() }
    }

	def getWorkload(host:String):Double = {
		if (connection == null) connection = Director.connectToDatabase
		val workloadSQL = "select time,value from scads,scads_metrics where scads_metrics.server=\""+host+"\" and request_type=\"ALL\" and stat=\"workload\" and aggregation=\""+interval+"\" and scads.metric_id=scads_metrics.id order by time desc limit 10"
		var value = Double.NaN
        val statement = connection.createStatement
		try {
			val result = statement.executeQuery(workloadSQL)
			val set = result.first // set cursor to first row
			if (set) value = (result.getLong("value")/interval/report_prob).toDouble
       	} catch { case ex: SQLException => println("Couldn't get workload"); ex.printStackTrace() }
		finally {statement.close}
		value
	}
	
	def getSingleMetric(host:String, metric:String, reqType:String):(java.util.Date,Double) = {
		if (connection == null) connection = Director.connectToDatabase
		//val workloadSQL = "select time,value from scads,scads_metrics where scads_metrics.server=\""+host+"\" and request_type=\""+reqType+"\" and stat=\""+metric+"\" and scads.metric_id=scads_metrics.id order by time desc limit 1"
		val workloadSQL = "select time,value from scads,scads_metrics where scads_metrics.server=\""+host+"\" and request_type=\""+reqType+"\" and stat=\""+metric+"\" and aggregation=\""+interval+"\" and scads.metric_id=scads_metrics.id order by time desc limit 1"
		var time:java.util.Date = null
		var value = Double.NaN
        val statement = connection.createStatement
		try {
			val result = statement.executeQuery(workloadSQL)
			val set = result.first // set cursor to first row
			if (set) {
				time = new java.util.Date(result.getLong("time"))
				value = if (metric=="workload") (result.getString("value").toDouble/interval/report_prob) else result.getString("value").toDouble
			}
       	} catch { case ex: SQLException => Director.logger.warn("SQL exception in metric reader",ex)}
		finally {statement.close}
		(time,value)
	}
	
	def getAllServers():List[String] = {
		if (connection == null) connection = Director.connectToDatabase
		val workloadSQL = "select distinct server from scads_metrics"
		var servers = new scala.collection.mutable.ListBuffer[String]()
        val statement = connection.createStatement
		try {
			val result = statement.executeQuery(workloadSQL)
			while (result.next) servers += result.getString("server")
       	} catch { case ex: SQLException => Director.logger.warn("SQL exception in metric reader",ex)}
		finally {statement.close}
		servers.toList
	}

}


case class ThriftMetricDBConnection (
	val host: String,
	val port: Int
) {
	val metricService = connectToMetricService(host,port)
	
	def connectToMetricService(host:String, port:Int): MetricServiceAPI.Client = {
		System.err.println("using MetricService at "+host+":"+port)
	
		var metricService: MetricServiceAPI.Client = null
		
        while (metricService==null) {
            try {
                val metricServiceTransport = new TSocket(host,port);
                val metricServiceProtocol = new TBinaryProtocol(metricServiceTransport);

                metricService = new MetricServiceAPI.Client(metricServiceProtocol);
                metricServiceTransport.open();
                System.err.println("connected to MetricService")

            } catch {
            	case e:Exception => {
	                e.printStackTrace
	                println("can't connect to the MetricService, waiting 60 seconds");
	                try {
	                    Thread.sleep(60 * 1000);
	                } catch {
	                    case e1:Exception => e1.printStackTrace()
	                }
                }
            }
        }
        metricService
	}	
	
}