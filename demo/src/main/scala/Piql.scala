package edu.berkeley.cs
package radlab
package demo

import scads.piql._
import scads.perf._

import java.sql.{ Connection, DriverManager, ResultSet, SQLException }
import net.lag.logging.Logger

object DashboardReportingExecutor {
  import DemoConfig._

  val logger = Logger()
  val responseTimeHist = Histogram(1, 1000)
  var lastReportTime = System.currentTimeMillis
  val hostName = java.net.InetAddress.getLocalHost.getHostName()

  val thread = new Thread("Dashboard Stats Reporting") {
    override def run(): Unit = {
      while (true) {
        Thread.sleep(30000)

        val oldHist = responseTimeHist.reset()
        val newReportTime = System.currentTimeMillis

        val respTime = oldHist.quantile(0.99)
        val reqRate = oldHist.totalRequests.toFloat / ((newReportTime - lastReportTime) / 1000)

        withConnection(conn => {
          val sqlInsertCmd = "INSERT INTO piqlReqRate (timestamp, host, aggRequestRate) VALUES (%d, '%s', %f, %f)".format(newReportTime, hostName, reqRate, respTime)
	  logger.info("Recording PIQL stats with: %s", sqlInsertCmd)

          val statment = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)
          if (statment.executeUpdate(sqlInsertCmd) != 1)
            logger.warning("Dashboard SQL statment failed: %s", sqlInsertCmd)
        })

        if (oldHist.totalRequests > 0) {

          logger.info("PIQL 99%%tile response time: %dms", respTime)
          logger.info("PIQL Request Rate: %f req/sec", reqRate)
        }
        lastReportTime = newReportTime
      }
    }
  }
  thread.setDaemon(true)
  thread.start()

  var cachedConnection: Option[Connection] = None
  def withConnection(f: Connection => Unit): Unit = {
    try {
      val currentConnection = cachedConnection.getOrElse {
        cachedConnection = Some(DriverManager.getConnection(dashboardDb).asInstanceOf[Connection])
        cachedConnection.get
      }
      f(currentConnection)
    } catch {
      case e: SQLException =>
        logger.warning(e, "Recording PIQL Stats to SQL Database failed")
        cachedConnection = None
    }
  }
}

/**
 * Records the elapsed time between open and close and reports it to the dashboard.
 */
class DashboardReportingExecutor extends QueryExecutor {
  val delegate = new ParallelExecutor

  def apply(plan: QueryPlan)(implicit ctx: Context): QueryIterator =
    new QueryIterator {
      val name = "DashboardReportingExecutor"
      private val childIterator = delegate(plan)
      private var startTime = 0L

      def open: Unit = {
        startTime = System.nanoTime
        childIterator.open
      }

      def close: Unit = {
        childIterator.close
        val endTime = System.nanoTime
        DashboardReportingExecutor.responseTimeHist.add((endTime - startTime) / 1000000)
        logger.debug("Query Executed in %d nanoseconds.", endTime - startTime)
      }

      def hasNext = childIterator.hasNext
      def next = childIterator.next
    }
}
