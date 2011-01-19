package deploylib
package mesos

import edu.berkeley.cs.scads.comm._
import edu.berkeley.cs.avro.runtime._
import org.apache.avro.io.JsonDecoder
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.generic.IndexedRecord

import net.lag.logging.Logger

abstract trait AvroTask extends IndexedRecord {
  val logger = Logger()

  def run(): Unit

  implicit def duplicate(process: JvmTask) = new {
    def *(count: Int): Seq[JvmTask] = Array.fill(count)(process)
  }

  def toJvmTask(implicit classpath: Seq[ClassSource]): JvmMainTask =
    JvmMainTask(classpath,
      "deploylib.mesos.AvroTaskMain",
      this.getClass.getName :: this.toJson :: Nil)
}

object AvroTaskMain {
  val logger = Logger()

  def main(args: Array[String]): Unit = {
    if (args.size == 2)
      try {
        val taskClass = Class.forName(args(0))
        val task = taskClass.newInstance.asInstanceOf[AvroTask]
        val reader = new SpecificDatumReader[AvroTask](task.getSchema)
        val decoder = new JsonDecoder(task.getSchema, args(1))

        reader.read(task, decoder).run()
        logger.info("Run method returned, terminating AvroClient")
        System.exit(0)
      } catch {
        case error => {
          logger.fatal(error, "Exeception in Main Thread.  Killing process.")
          System.exit(-1)
        }
      }
    else {
      println("Usage: " + this.getClass.getName + "<class name> <json encoded avro client description>")
      System.exit(-1)
    }
  }
}
