package edu.berkeley.cs.scads.comm

import scala.concurrent.SyncVar
import scala.actors._
import scala.actors.Actor._

import org.apache.log4j.Logger

import com.googlecode.avro.marker.AvroRecord
import com.googlecode.avro.annotation.AvroUnion

object Actors {
  import scala.actors._
  import scala.actors.Actor._

  /* TODO: link to the created actor and unregister on exit */
  def remoteActor(body: (RemoteActorProxy) => Unit): Actor = {
    val a = new Actor() {
      def act(): Unit = {
        val ra = MessageHandler.registerActor(self)
        body(ra)
        MessageHandler.unregisterActor(ra)
      }
    }
    a.start
    return a
  }
}

/* Generic Remote Actor Handle */
case class RemoteActor(var host: String, var port: Int, var id: ActorId) extends RemoteActorProxy with AvroRecord

/* Specific types for different services. Note: these types are mostly for readability as typesafety isn't enforced when serialized individualy*/
case class StorageService(var host: String, var port: Int, var id: ActorId) extends RemoteActorProxy with AvroRecord
case class PartitionService(var host: String, var port: Int, var id: ActorId) extends RemoteActorProxy with AvroRecord

case class TimeoutException(msg: MessageBody) extends Exception

/* This is a placeholder until stephen's remote actor handles are available */
trait RemoteActorProxy {
  var host: String
  var port: Int
  var id: ActorId

  def remoteNode = RemoteNode(host, port)

  override def toString(): String = id + "@" + host + ":" + port

  /**
   * Returns an ouput proxy that forwards any messages to the remote actor with and empty sender.
   */
  def outputChannel = new OutputChannel[Any] {
    def !(msg: Any):Unit = msg match {
      case msgBody: MessageBody => MessageHandler.sendMessage(remoteNode, Message(None, id, None, msgBody))
      case otherMessage => throw new RuntimeException("Invalid remote message type:" + otherMessage + " Must extend MessageBody.")
    }
    def forward(msg: Any):Unit = throw new RuntimeException("Unimplemented")
    def send(msg: Any, sender: OutputChannel[Any]):Unit = throw new RuntimeException("Unimplemented")
    def receiver: Actor = throw new RuntimeException("Unimplemented")
  }

  /**
   * Send a message asynchronously.
   **/
  def !(body: MessageBody)(implicit sender: RemoteActorProxy): Unit = {
    MessageHandler.sendMessage(remoteNode, Message(Some(sender.id), id, None, body))
  }

  /**
   * Send a message and synchronously wait for a response.
   */
  def !?(body: MessageBody, timeout: Int = 5000): MessageBody = {
      val future = new MessageFuture
      this.!(body)(future.remoteActor)
      future.get(timeout) match {
        case Some(exp: RemoteException) => throw new RuntimeException(exp.toString)
        case Some(msg: MessageBody) => msg
        case None => {
          future.cancel
          throw TimeoutException(body)
        }
      }
  }

  /**
   * Sends a message and returns a future for the response.
   */
  def !!(body: MessageBody): MessageFuture = {
    val future = new MessageFuture
    this.!(body)(future.remoteActor)
    future
  }

  /* Handle type conversion methods.  Note: Not typesafe obviously */
  def toPartitionService: PartitionService = new PartitionService(host, port, id)
  def toStorageService: StorageService = new StorageService(host, port, id)
}
