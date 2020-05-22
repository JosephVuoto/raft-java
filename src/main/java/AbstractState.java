import java.io.Serializable;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.Random;
import org.apache.log4j.Logger;

public abstract class AbstractState {
	/* Use for log */
	static protected Logger logger;
	/* time interval for the leader to send heartbeat messages */
	protected static final int HEART_BEAT_INTERVAL = 500;
	/* timeout for the follower to start a new election; this timeout should be random to prevent live lock. we define
	 * an upper and lower bound here  */
	protected static final int ELECTION_TIME_OUT_MIN = 2000;
	protected static final int ELECTION_TIME_OUT_MAX = 5000;
	/* the actual election timeout */
	protected static int electionTimeout;
	/* latest term server has seen (initialized to 0 on first boot, increases monotonically) updated on stable storage
	 * before responding to RPCs */
	protected static int currentTerm = 0;
	/* candidateId that received vote in current term (or -1 if none). updated on stable storage before responding to
	 * RPCs */
	protected static int votedFor = -1;
	/* index of highest log entry known to be committed (initialized to 0, increases monotonically) */
	protected static int commitIndex = 0;
	/* index of highest log entry applied to state machine (initialized to 0, increases monotonically) */
	protected static int lastApplied = 0;
	/* path for persistent states: currentTerm, votedFor, logEntries,
	    will be initialized when the node starts, in NodeStarter  */
	private static String statePersistencePath = "./state.json";

	/* The node itself */
	protected NodeImpl node;

	public AbstractState(NodeImpl node) {
		this.node = node;
		/* pick a election timeout randomly */
		electionTimeout = ELECTION_TIME_OUT_MIN + new Random().nextInt(ELECTION_TIME_OUT_MAX - ELECTION_TIME_OUT_MIN);
	}

	/**
	 * Start acting as the concrete role (follower, candidate or leader)
	 */
	abstract public void start();

	/**
	 * Invoked by candidates to gather votes.
	 *
	 * @param term         candidate’s term
	 * @param candidateId  candidate requesting vote
	 * @param lastLogIndex index of candidate’s last log entry
	 * @param lastLogTerm  term of candidate’s last log entry
	 * @return VoteResponse object containing whether the vote was granted and the recipient's current term
	 */
	abstract public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm);

	/**
	 * Invoked by leader to replicate log entries; also used as heartbeat.
	 *
	 * @param term         leader’s term
	 * @param leaderId     so follower can redirect clients
	 * @param prevLogIndex index of log entry immediately preceding new ones
	 * @param prevLogTerm  term of prevLogIndex entry
	 * @param entries      log entries to store (empty for heartbeat;
	 *                     may send more than one for efficiency)
	 * @param leaderCommit leader’s commitIndex
	 * @return AppendResponse object containing whether the append was successful and the recipient's current term
	 */
	abstract public AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
	                                             LogEntry[] entries, int leaderCommit);

	/**
	 * Handle the command from user's command line
	 * @param command Command string e.g. "set id 1"
	 * @param timeout in millisecond
	 * @return result
	 */
	abstract public String handleCommand(String command, int timeout);
	/**
	 * Persistent state on all servers:
	 * (Updated on stable storage before responding to RPCs)
	 * currentTerm, votedFor, log[]
	 */
	protected synchronized void writePersistentState() {
		PersistentState state = new PersistentState();
		state.setVoteFor(votedFor);
		state.setCurrentTerm(currentTerm);
		state.setLogEntries(node.getRaftLog().getLogEntries());
		JsonFileUtil.writePersistentState(statePersistencePath, state);
	}

	/**
	 * Immutable class to represent a response to a vote request
	 */
	public static class VoteResponse implements Serializable {
		public final boolean voteGranted;
		public final int currentTerm;

		public VoteResponse(boolean voteGranted, int currentTerm) {
			logger.info("VoteGranted: " + voteGranted);
			this.voteGranted = voteGranted;
			this.currentTerm = currentTerm;
		}
	}

	/**
	 * Immutable class to represent a response to an append entries request
	 */
	public static class AppendResponse implements Serializable {
		public final boolean success;
		public final int term;

		public AppendResponse(boolean success, int term) {
			this.success = success;
			this.term = term;
		}

		@Override
		public String toString() {
			return "AppendResponse{"
			    + "success=" + success + ", term=" + term + '}';
		}
	}

	public static void setStatePersistencePath(String statePersistencePath) {
		AbstractState.statePersistencePath = statePersistencePath;
	}

	/**
	 * Recover from crashing
	 * @param state persistent state from a file
	 */
	public void restorePersistentState(PersistentState state) {
		votedFor = state.getVoteFor();
		currentTerm = state.getCurrentTerm();
		node.getRaftLog().setLogEntries(state.getLogEntries());
	}

	/**
	 * Retry to connect to the remote node
	 * @param remoteId the Id of the remoteNode
	 */
	protected boolean refindRemoteNode(int remoteId) {
		String remoteUrl = node.getRemoteUrl(remoteId);
		try {
			// Reconnect to the node and call the remote appendEntries again
			INode newRemoteNode = (INode)Naming.lookup(remoteUrl);
			node.updateRemoteNode(remoteId, newRemoteNode);
			logger.debug("Reconnected to node #" + remoteId);
		} catch (NotBoundException | MalformedURLException | RemoteException notBoundException) {
			// Retry next time
			// logger.debug(notBoundException);
			return false;
		}
		return true;
	}

	/**
	 * Set voted for
	 */
	protected void setVoteFor(int newVotedFor) {
		votedFor = newVotedFor;
		writePersistentState();
	}

	/**
	 * Ser currentTerm and store it persistently.
	 *
	 * @param term term num
	 */
	protected void setCurrentTerm(int term) {
		if (term > currentTerm) {
			votedFor = -1;
			currentTerm = term;
			writePersistentState();
		}
	}
}