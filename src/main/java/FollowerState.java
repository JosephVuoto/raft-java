import java.util.Random;

public class FollowerState extends AbstractState {

	public FollowerState(NodeImpl node) {
		super(node);
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for heartbeat and start it
	 */
	public void start() {
		// TODO: initialize the follower state
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// TODO: determine whether to vote for the caller and update the timer
		return null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	public int appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                         int leaderCommit) {
		// TODO: determine whether to append entries from the caller and update the timer
		return 0;
	}
}
