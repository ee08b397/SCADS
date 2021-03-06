import edu.berkeley.cs.scads.storage._
import edu.berkeley.cs.scads.comm._
import edu.berkeley.cs.scads.perf._
import edu.berkeley.cs.scads.piql._
import edu.berkeley.cs.scads.piql.scadr._
import edu.berkeley.cs.scads.piql.tpcw._
import edu.berkeley.cs._
import deploylib.mesos._
import deploylib.ec2._

def debug(pkg: String) = net.lag.logging.Logger(pkg).setLevel(java.util.logging.Level.FINEST)
