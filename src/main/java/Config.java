import java.util.List;

/**
 * Data structure of the config json file. the json will look like this:
 * {
 *     "nodeId":1,
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
	private int nodeId;
	/* Information of the cluster (other nodes) */
	private List<NodeInfo> clusterInfo;

	public static class NodeInfo {
		/* node ID */
		private int nodeId;
		/* IP address */
		private String address;
		/* Port number */
		private int port;
	}
}
