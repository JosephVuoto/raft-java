import java.util.HashMap;
import java.util.Map;

public class LeaderState extends AbstractState {

	/* a map that stores for each server, index of the next log entry to send
	   to that server (initialized to leader last log index + 1). The data
	   structure
		is <node id, index of the next log entry>. Reinitialized after election
	 */
	private Map<Integer, Integer> nextIndex;
	/* for each server, index of highest log entry known to be replicated on
		server (initialized to 0, increases monotonically).  The data structure
		 is <node id, index of highest log entry...>. Reinitialized after
	   election */
	private Map<Integer, Integer> matchIndex;

	public LeaderState(NodeImpl node) {
		super(node);
		nextIndex = new HashMap<>();
		matchIndex = new HashMap<>();
	}

	/**
	 * Initialize attributes and setup a timer to send heartbeat message
	 * regularly
	 */
	public void start() {
		// TODO: initialize the leader state
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public int requestVote(int term, int candidateId, int lastLogIndex,
						   int lastLogTerm) {
		// TODO: if receive this RPC from a stale candidate (whose term is less
		// than its term), send the current term number to reject the vote.
		// otherwise go back to follower state
		return 0;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	public int appendEntries(int term, int leaderId, int prevLogIndex,
							 int prevLogTerm, LogEntry[] entries,
							 int leaderCommit) {
		// TODO: similar to requestVote() in this class, check the validation
		// of the RPC
		return 0;
	}
}
