import java.util.List;

/**
 * Data structure of the config json file. the json will look like this:
 * {
 *     "nodeId":1,
 *     "port":2222,
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
	/* Current node ID */
	public final int nodeId;
	/* Information of the cluster (other nodes) */
	public final List<NodeInfo> clusterInfo;
	/* Local port */
	public final int port;

	public Config(int nodeId, List<NodeInfo> clusterInfo, int port) {
		this.nodeId = nodeId;
		this.clusterInfo = clusterInfo;
		this.port = port;
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
	}
}
