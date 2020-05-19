import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Starter class to start a node
 */
public class NodeStarter {
	public static void main(String[] args) {
		String configPath = "./config.json";
		if (args.length == 1) {
			configPath = args[0];
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
					try {
						/* Get the INode stub from remote registry */
						INode remoteNode = (INode)Naming.lookup(remoteUrl);
						/* Add to the list */
						remoteNodeList.add(remoteNode);
					} catch (NotBoundException | MalformedURLException | RemoteException e) {
						e.printStackTrace();
					}
					countDownLatch.countDown();
				});
			}

			/* Wait until all thread are finished, so that the remoteNodeList contains all remote nodes */
			countDownLatch.await();
			node.setRemoteNodes(remoteNodeList);
			/* Start the node! */
			node.setState(new FollowerState(node));
		} catch (RemoteException | MalformedURLException | InterruptedException e) {
			e.printStackTrace();
		}
	}
}
