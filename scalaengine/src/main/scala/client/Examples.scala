package edu.berkeley.cs
package scads
package storage

import client._

import edu.berkeley.cs.avro.marker._

import edu.berkeley.cs.scads.comm._
import edu.berkeley.cs.scads.util._

import org.apache.avro._
import generic._
import specific._

import java.util.Comparator

// TODO: change Manifest to ClassManifest
class GenericNamespace(
    val name: String,
    val cluster: ScadsCluster,
    val root: ZooKeeperProxy#ZooKeeperNode,
    val keySchema: Schema,
    val valueSchema: Schema,
    val valueClass: String)
  extends Namespace
    with SimpleRecordMetadata
  with ZooKeeperGlobalMetadata
  with DefaultKeyRangeRoutable
  with QuorumRangeProtocol
  with AvroGenericKeyValueSerializer
  with RangeKeyValueStore[GenericRecord, GenericRecord] {

  /** this is a HACK for now, to handle integration with the director */
  def typedRoutingTable: RangeTable[GenericRecord, PartitionService] = {
    val _rt = routingTable
    val newRTable = _rt.rTable.map(rt => new RangeType[GenericRecord, PartitionService](rt.startKey.map(bytesToKey), rt.values))
    val keyComp = new Comparator[RangeType[GenericRecord, PartitionService]] {
      def compare(
          lhs: RangeType[GenericRecord, PartitionService], 
          rhs: RangeType[GenericRecord, PartitionService]): Int = {
        _rt.keyComparator.compare(
          new RangeType[Array[Byte], PartitionService](lhs.startKey.map(keyToBytes), lhs.values),
          new RangeType[Array[Byte], PartitionService](rhs.startKey.map(keyToBytes), rhs.values)
        ) 
      }
    }
    new RangeTable[GenericRecord, PartitionService](newRTable, keyComp, _rt.mergeCondition)
  }

  val statslogger = net.lag.logging.Logger("statsprotocol")

  /**
  * For each range represented by me, get the worklad stats from all the partitions
  * Add stats from partitions that cover the same range, dividing by the quorum amount
  * (Do this to get "logical" workload to a partition)
  * Return mapping of startkey to tuple of (gets,puts)
  * TODO: is this assuming that the read/write quorum will never change?
  */
  def getWorkloadStats(time:Long):Map[Option[GenericRecord], (Long,Long)] = {
    var haveStats = false
    val ranges = serversForKeyRange(None,None)
    val requests = for (fullrange <- ranges) yield {
      (fullrange.startKey,fullrange.endKey, fullrange.servers, for (partition <- fullrange.servers) yield {partition !! GetWorkloadStats()} )
    }
    val result = Map( requests.map(range => {
      var gets = 0L; var puts = 0L
      val readDivisor = range._4.size//scala.math.ceil(range._4.size * readQuorum).toLong
      val writeDivisor = range._4.size//scala.math.ceil(range._4.size * writeQuorum).toLong

      range._4.foreach(resp => { val (getcount,putcount,statstime) = resp.get match {
        case r:GetWorkloadStatsResponse => (r.getCount.toLong,r.putCount.toLong,r.statsSince.toLong); case _ => (0L,0L,0L)
        }; gets += getcount; puts += putcount; statslogger.debug("workload stats for: %s",statstime.toString); if (statstime >= time) haveStats = true} )
      (range._1.map(bytesToKey) -> ( gets/readDivisor , puts/writeDivisor ))
    }):_* )

    if (haveStats) result else null
  }
}

class GenericHashNamespace(
    val name: String,
    val cluster: ScadsCluster,
    val root: ZooKeeperProxy#ZooKeeperNode,
    val keySchema: Schema,
    val valueSchema: Schema,
    val valueClass: String)
  extends Namespace
  with SimpleRecordMetadata
  with ZooKeeperGlobalMetadata
  with DefaultHashKeyRoutable
  with QuorumProtocol
  with AvroGenericKeyValueSerializer
  with KeyValueStore[GenericRecord, GenericRecord]

class SpecificNamespace[Key <: SpecificRecord : Manifest, Value <: SpecificRecord : Manifest](
    val name: String,
    val cluster: ScadsCluster,
    val root: ZooKeeperProxy#ZooKeeperNode)
  extends Namespace
  with SimpleRecordMetadata
  with ZooKeeperGlobalMetadata
  with DefaultKeyRangeRoutable
  with QuorumRangeProtocol
  with AnalyticsProtocol
  with AvroSpecificKeyValueSerializer[Key, Value]
  with RangeKeyValueStore[Key, Value] {

  override protected val keyManifest = manifest[Key]
  override protected val valueManifest = manifest[Value] 

  lazy val genericNamespace: GenericNamespace = {
    val generic = new GenericNamespace(name, cluster, root, keySchema, valueSchema, valueClass)
    generic.open()
    generic
  }
}

class SpecificInMemoryNamespace[Key <: SpecificRecord : Manifest, Value <: SpecificRecord : Manifest](
    val imname: String,
    val imcluster: ScadsCluster,
    val imroot: ZooKeeperProxy#ZooKeeperNode) extends SpecificNamespace[Key,Value](imname,imcluster,imroot) {

  override protected val keyManifest = manifest[Key]
  override protected val valueManifest = manifest[Value] 

  override def partitionType:String = "inmemory"
}

class SpecificHashNamespace[Key <: SpecificRecord : Manifest, Value <: SpecificRecord : Manifest](
    val name: String,
    val cluster: ScadsCluster,
    val root: ZooKeeperProxy#ZooKeeperNode)
  extends Namespace
  with SimpleRecordMetadata
  with ZooKeeperGlobalMetadata
  with DefaultHashKeyRoutable
  with QuorumProtocol
  with AvroSpecificKeyValueSerializer[Key, Value]
  with KeyValueStore[Key, Value] {

  override protected val keyManifest = manifest[Key]
  override protected val valueManifest = manifest[Value] 

}

class PairNamespace[Pair <: AvroPair : Manifest](
    val name: String,
    val cluster: ScadsCluster,
    val root: ZooKeeperProxy#ZooKeeperNode)
  extends Namespace
  with SimpleRecordMetadata
  with ZooKeeperGlobalMetadata 
  with DefaultKeyRangeRoutable
  with QuorumRangeProtocol
  with AvroPairSerializer[Pair]
  with RecordStore[Pair]
  with index.IndexManager[Pair]
  with DebuggingClient 
  with NamespaceIterator[Pair] {
  
  override protected val pairManifest = manifest[Pair]

  def schema: Schema = pairSchema

  def asyncGetRecord(key: IndexedRecord): ScadsFuture[Option[Pair]] = {
    val keyBytes = keyToBytes(key)
    asyncGetBytes(keyBytes) map (_.map(bytesToBulk(keyBytes, _)))
  }

  def getRecord(key: IndexedRecord): Option[Pair] = {
    val keyBytes = keyToBytes(key)

    getBytes(keyBytes).map(bytesToBulk(keyBytes, _))
  }

  def put(pairRec: Pair): Unit = {
    put(pairRec.key, Some(pairRec.value))
  }

  def delete(pairRec: Pair): Unit = {
    put(pairRec.key, None)
  }
}
