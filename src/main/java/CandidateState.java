public class CandidateState extends AbstractState {

	public CandidateState(NodeImpl node) {
		super(node);
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for election and start it
	 */
	public void start() {
		// TODO: initialize the candidate state
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// TODO: determine whether to vote for the caller. if yes, then the node should
		// go back to follower state, if not, return the current term number.
		return null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	public AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                                    int leaderCommit) {
		// TODO: if receive this RPC with a higher term number, then go back to follower
		// state. if not, return the current term number.
		return null;
	}
}
