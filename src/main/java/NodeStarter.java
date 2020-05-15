import java.net.MalformedURLException;
import java.rmi.AlreadyBoundException;
import java.rmi.Naming;
import java.rmi.RemoteException;

/**
 * Starter class to start a node
 */
public class NodeStarter {
    public static void main(String[] args) {
        //TODO: start a node:
        //1. read serverId, registry address from a config file
        //2. create a NodeImpl
        //3. register the remote object
        //4. set a state (follower) to the node, then start it
        int serverId = 0;
        String url = ".....";
        try {
            NodeImpl node = new NodeImpl(serverId);
            Naming.bind(url, node);
            node.setState(new FollowerState(node));
        } catch (RemoteException | MalformedURLException | AlreadyBoundException e) {
            e.printStackTrace();
        }
    }
}
