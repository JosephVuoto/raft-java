import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;

public class NodeImpl extends UnicastRemoteObject implements INode, IClientInterface {
	static final Logger logger = Logger.getLogger(NodeImpl.class.getName());

	/* A unique identifier of the node */
	private int nodeId;
	/* The node's state (follower, candidate or leader) */
	private AbstractState state;
	/* log entries */
	private RaftLog raftLog = new RaftLog();
	/* list of remote nodes in the cluster */
	private Map<Integer, INode> remoteNodes;
	private Map<Integer, String> remoteUrls;

	private static int AECount = 0;

	/**
	 * Constructor
	 *
	 * @param nodeId the unique ID
	 * @throws RemoteException communication-related exception
	 */
	protected NodeImpl(int nodeId) throws RemoteException {
		this.nodeId = nodeId;
	}

	public void setState(AbstractState state) {
		System.out.println();
		logger.info("Node #" + nodeId + " now is in " + state.getClass().getSimpleName());
		this.state = state;
		state.start();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see INode#requestVote(int, int, int, int)
	 */
	@Override
	public AbstractState.VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm)
	    throws RemoteException {
		System.out.println();
		logger.info("###### RE FROM: " + candidateId + " ######");
		return state.requestVote(term, candidateId, lastLogIndex, lastLogTerm);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see INode#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	@Override
	public AbstractState.AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
	                                                  LogEntry[] entries, int leaderCommit) throws RemoteException {
		AECount += 1;
		System.out.println();
		logger.info("###### AE:" + AECount + " ######");
		logger.info("Node ID: " + nodeId + " State: " + state.getClass().getSimpleName() +
		            " Term: " + AbstractState.currentTerm);
		logger.info("AE: FromLeaderID: " + leaderId + " pLIndex = " + prevLogIndex + ", pLTerm = " + prevLogTerm +
		            ", term = " + term);
		return state.appendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
	}

	public int getNodeId() {
		return nodeId;
	}

	/* Auto-generated getters and setters */
	public void setNodeId(int nodeId) {
		this.nodeId = nodeId;
	}

	public RaftLog getRaftLog() {
		return raftLog;
	}

	public void setRaftLog(RaftLog raftLog) {
		this.raftLog = raftLog;
	}

	public Map<Integer, INode> getRemoteNodes() {
		return remoteNodes;
	}

	public void setRemoteNodes(Map<Integer, INode> remoteNodes) {
		this.remoteNodes = remoteNodes;
	}

	public void setRemoteUrls(Map<Integer, String> remoteUrls) {
		this.remoteUrls = remoteUrls;
	}

	public void updateRemoteNode(int id, INode node) {
		remoteNodes.put(id, node);
	}

	public String getRemoteUrl(int id) {
		return remoteUrls.get(id);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see IClientInterface#sendCommand(String, int)
	 */
	@Override
	public String sendCommand(String command, int timeout) throws RemoteException {
		logger.info("node #" + nodeId + ": received command: " + command);
		String[] commandArgs = command.split("\\s+");
		if (commandArgs.length == 0) {
			return "Invalid command";
		}
		if ("get".equals(commandArgs[0])) {
			if (commandArgs.length != 2) {
				return "Invalid command";
			} else {
				return raftLog.getStateMachine().get(commandArgs[1]);
			}
		} else if ("set".equals(commandArgs[0])) {
			if (commandArgs.length != 3) {
				return "Invalid command";
			} else {
				return state.handleCommand(command, timeout);
			}
		} else if ("del".equals(commandArgs[0])) {
			if (commandArgs.length != 2) {
				return "Invalid command";
			} else {
				return state.handleCommand(command, timeout);
			}
		} else if ("keys".equals(commandArgs[0])) {
			if (commandArgs.length != 1) {
				return "Invalid command";
			} else {
				Set<String> keySet = raftLog.getStateMachine().keys();
				StringBuilder returnValue = new StringBuilder();
				int id = 1;
				for (String key : keySet) {
					returnValue.append(id++).append(") ").append("\"").append(key).append("\"\n");
				}
				return returnValue.toString();
			}
		}
		return "Invalid command";
	}
}
