abstract class ClusterMembership(dp: DataPlacement) {
	/* State */
	val placement = dp			// instance of Data Placement class
	var members: Set[Node] 		// list of nodes and their status
	
	/* Methods */
	private def add_node (node: Node) = {		// add to cluster list
		members += node->"active"
		dp.add_node(node)
	}
	
	private def remove_node(node: Node)	= {	// remove from cluster list
		members -= node
		dp.remove_node(node)
	}
}

abstract class SimpleCluster(dp: DataPlacement) extends ClusterMembership(dp) {
	/* Methods */
	def join(node: Node) 	// a node joins the cluster; cluster membership informs its placement instance
	def leave(node: Node) 	// a node leaves, cluster memberships informs placement
}

abstract class MulticastCluster(dp: DataPlacement) extends ClusterMembership(dp) {
	// TODO
}

trait HeartBeat {
	/* State */
	def beat_interval: Int  // how often to ping a node in the cluster
	def timeout: Int		// when is a node considered dead
}
