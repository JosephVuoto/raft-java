import java.util.List;

/**
 * Data structure of the config json file. the json will look like this:
 * {
 *     "nodeId":1,
 *     "port":2222,
 *     "statePath":"./state.json",
 *     "clusterInfo":[
 *         {
 *             "nodeId":2,
 *             "address":"127.0.0.1",
 *             "port":3333
 *         },
 *         {
 *             "nodeId":3,
 *             "address":"127.0.0.1",
 *             "port":4444
 *         },
 *         {
 *             "nodeId":4,
 *             "address":"127.0.0.1",
 *             "port":5555
 *         }
 *     ]
 * }
 */
public class Config {
	/* Current node ID, null if it is config for client */
	public final Integer nodeId;
	/* Local port, null if it is config for client */
	public final Integer port;
	/* path for persistent states: currentTerm, votedFor, logEntries */
	public final String statePath;
	/* Information of the cluster (other nodes) */
	public final List<NodeInfo> clusterInfo;

	public Config(Integer nodeId, Integer port, String statePath, List<NodeInfo> clusterInfo) {
		this.nodeId = nodeId;
		this.port = port;
		this.statePath = statePath;
		this.clusterInfo = clusterInfo;
	}

	public static class NodeInfo {
		/* node ID */
		public final int nodeId;
		/* IP address */
		public final String address;
		/* Port number */
		public final int port;

		public NodeInfo(int nodeId, String address, int port) {
			this.nodeId = nodeId;
			this.address = address;
			this.port = port;
		}

		@Override
		public String toString() {
			return "NodeInfo: {"
			    + " nodeId = " + nodeId + ", address = '" + address + '\'' + ", port = " + port + " }";
		}
	}
}
