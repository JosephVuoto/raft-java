import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

public class NodeImpl extends UnicastRemoteObject implements INode, IClientInterface {
	/* A unique identifier of the node */
	private int nodeId;
	/* The node's state (follower, candidate or leader) */
	private AbstractState state;
	/* log entries */
	private RaftLog raftLog;
	/* list of remote nodes in the cluster */
	private List<INode> remoteNodes;

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
		return state.appendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
	}

	/* Auto-generated getters and setters */
	public int getNodeId() {
		return nodeId;
	}

	public void setNodeId(int nodeId) {
		this.nodeId = nodeId;
	}

	public RaftLog getRaftLog() {
		return raftLog;
	}

	public void setRaftLog(RaftLog raftLog) {
		this.raftLog = raftLog;
	}

	public List<INode> getRemoteNodes() {
		return remoteNodes;
	}

	public void setRemoteNodes(List<INode> nodes) {
		remoteNodes = nodes;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see IClientInterface#sendCommand(String)
	 */
	@Override
	public String sendCommand(String command) throws RemoteException {
		// TODO: deal with commands
		return "Stub";
	}
}
