import java.util.Random;

public abstract class AbstractState {
	/* time interval for the leader to send heartbeat messages */
	protected static final int HEART_BEAT_INTERVAL = 500;

	/* timeout for the follower to start a new election; this timeout should be random to prevent live lock. we define
	 * an upper and lower bound here  */
	protected static final int ELECTION_TIME_OUT_MIN = 1000;
	protected static final int ELECTION_TIME_OUT_MAX = 2000;
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
	 * @return if the node accepts the request, return 0. If rejects the request,
	 * returns its currentTerm, for leader to update itself
	 */
	abstract public int appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                                  int leaderCommit);

	/**
	 * Immutable class to represent a response to a vote request
	 */
	public static class VoteResponse {
		public final boolean voteGranted;
		public final int currentTerm;

		public VoteResponse(boolean voteGranted, int currentTerm) {
			this.voteGranted = voteGranted;
			this.currentTerm = currentTerm;
		}
	}
}
