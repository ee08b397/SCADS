package edu.berkeley.cs.scads
package storage
package transactions
package mdcc


import edu.berkeley.cs.scads.storage.transactions.{Tx, ProtocolBase}
import actors.Actor
import util.Logger._
import comm.RemoteService
import actors.Actor._
import collection.mutable.HashSet
import util.{Scheduler, Logger}
import actors.TIMEOUT
import java.util.concurrent.Semaphore
import collection.mutable.HashSet

//import tools.nsc.matching.ParallelMatching.MatchMatrix.VariableRule
object MDCCProtocol extends ProtocolBase {
  def RunProtocol(tx: Tx) = {
    val trxHandler = new MDCCTrxHandler(tx)
    trxHandler.execute()
  }
}

class MDCCTrxHandler(tx: Tx) extends Actor {
  @volatile var status: TxStatus = UNKNOWN
  var Xid = ScadsXid.createUniqueXid()
  var count = 0
  var size = 0
  var participants = collection.mutable.HashSet[MCCCRecordHandler]()

  private val sema = new Semaphore(0, false)

  protected val logger = Logger(classOf[MDCCTrxHandler])

  implicit val remoteHandle = StorageRegistry.registerActor(this).asInstanceOf[RemoteService[StorageMessage]]

  def execute() = {
    this.start()
    logger.debug("Waiting for status")
    sema.acquire()
    logger.debug("We got a status")
    status match {
      case UNKNOWN => tx.unknownFn()
      case COMMITTED=> tx.commitFn(COMMITTED)
      case ABORTED => tx.commitFn(ABORTED)
    }
  }


  protected def startTrx(updateList: UpdateList, readList: ReadList) = {
    updateList.getUpdateList.foreach(update => {
      size += 1
      update match {
        case ValueUpdateInfo(ns, servers, key, value) => {
          val oldRrecord = readList.getRecord(key)
          val md : MDCCMetadata = oldRrecord.map(_.metadata).getOrElse(ns.getDefaultMeta())
          //TODO: Do we really need the MDCCMetadata
          val propose = Propose(Xid, ValueUpdate(key, oldRrecord.flatMap(_.value), value.get))  //TODO: We need a read-strategy
          val rHandler = ns.recordCache.getOrCreate(key, CStruct(value, Nil), servers, md, ns.getConflictResolver)
          participants += rHandler
          logger.info("" + Xid + ": Sending physical update propose to MCCCRecordHandler", propose)
          rHandler.remoteHandle ! propose
        }
        case LogicalUpdateInfo(ns, servers, key, value) => {
          val md = readList.getRecord(key).map(_.metadata).getOrElse(ns.getDefaultMeta())
          val propose = Propose(Xid, LogicalUpdate(key, value.get))
          val rHandler = ns.recordCache.getOrCreate(key, CStruct(value, Nil), servers, md, ns.getConflictResolver)  //TODO: Gene is the CStruct correct?
          participants += rHandler
          logger.debug("" + Xid + ": Sending logical update propose to MCCCRecordHandler", propose)
          rHandler.remoteHandle ! propose
        }
      }
    })
  }

  def notifyAcceptors() = {
    if (status == COMMITTED)
      participants.foreach(_.remoteHandle !  edu.berkeley.cs.scads.storage.Commit(Xid))
    else if(status == ABORTED)
      participants.foreach(_.remoteHandle !  edu.berkeley.cs.scads.storage.Abort(Xid))
    else assert(false)
  }

  def act() {
    logger.debug("Starting to wait for messages. Setting timeout:" + tx.timeout)
    Scheduler.schedule(() => {
      this ! TIMEOUT}, tx.timeout)
    startTrx(tx.updateList, tx.readList)
    loop {
      react {
        case StorageEnvelope(src, msg@Learned(_, _, success)) => {
          if(success) {
            count += 1
            logger.debug("" + Xid + ": Receive record commit")
            if(count == size && status == UNKNOWN){
              logger.info("Transaction " + Xid + " committed")
              status = COMMITTED
              this ! EXIT
            }
          }else{
            if(status == UNKNOWN) {
              logger.debug("" + Xid + ": Receive record abort")
              logger.info("Transaction " + Xid + " aborted")
              status = ABORTED
              this ! EXIT
            }
          }
        }
        case EXIT => {
          sema.release()
          logger.debug("" + Xid + ": Exit requested")
          notifyAcceptors
          StorageRegistry.unregisterService(remoteHandle)
          exit()
        }
        case TIMEOUT => {
          logger.debug("" + Xid + "Time out")
          sema.release()
        }
        case msg@_ =>
          throw new RuntimeException("Unknown message" + msg)

      }
    }
  }
}
