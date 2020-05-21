import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.ConnectException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.log4j.Logger;

/**
 * Starter class to start a node
 */
public class NodeStarter {
	static final Logger logger = Logger.getLogger(NodeStarter.class.getName());

	public static void main(String[] args) {
		String configPath = null;
		if (args.length == 1) {
			configPath = args[0];
		} else {
			ClassLoader classLoader = NodeStarter.class.getClassLoader();
			URL resource = classLoader.getResource("nodeConfig.json");
			if (resource != null) {
				configPath = resource.getPath();
			}
		}
		if (configPath == null) {
			System.err.println("No config file!");
			return;
		}
		Config config = JsonFileUtil.readConfig(configPath);
		int nodeId = config.nodeId;
		int port = config.port;
		/* The number of other nodes */
		List<Config.NodeInfo> clusterInfo = config.clusterInfo;
		/* Url for naming binding. e.g. rmi://localhost:2222/node1 */
		String url = "rmi://localhost:" + port + "/node" + nodeId;
		try {
			/* Create a local rmi registry */
			LocateRegistry.createRegistry(port);
			/* Create INode instance and bind it with the rmi url */
			NodeImpl node = new NodeImpl(nodeId);
			Naming.rebind(url, node);

			ExecutorService service = Executors.newFixedThreadPool(clusterInfo.size());
			final CountDownLatch countDownLatch = new CountDownLatch(clusterInfo.size());
			/* A list of remote nodes */
			List<INode> remoteNodeList = new ArrayList<>();

			for (Config.NodeInfo info : clusterInfo) {
				/* Submit a new task to the thread pool */
				service.submit(() -> {
					/* Construct a rmi url for the remote node */
					String remoteUrl = "rmi://" + info.address + ":" + info.port + "/node" + info.nodeId;
					/* Retry connecting for 30 * 1s, wait for other node to start */
					int retryTimes = 30, retryInterval = 1000;
					try {
						/* Get the INode stub from remote registry */
						for (int i = 0; i < retryTimes; i++) {
							try {
								INode remoteNode = (INode)Naming.lookup(remoteUrl);

								/* Succeed, add to the list */
								remoteNodeList.add(remoteNode);
								logger.info("Connected to node #" + info.nodeId);
								break;
							} catch (ConnectException | NotBoundException e) {
								Thread.sleep(retryInterval);
							}
						}
					} catch (MalformedURLException | RemoteException | InterruptedException e) {
						e.printStackTrace();
					}
					countDownLatch.countDown();
				});
			}

			/* Wait until all thread are finished, so that the remoteNodeList contains all remote nodes */
			countDownLatch.await();
			node.setRemoteNodes(remoteNodeList);
			/* Set path for persistent states */
			AbstractState.setStatePersistencePath(config.statePath);
			PersistentState state = JsonFileUtil.readPersistentState(config.statePath);
			/* Start the node! */
			AbstractState initialState = new FollowerState(node);
			node.setState(initialState);
			if (state != null) {
				initialState.restorePersistentState(state);
			}
		} catch (RemoteException | MalformedURLException | InterruptedException e) {
			e.printStackTrace();
		}
	}
}
