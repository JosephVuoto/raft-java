import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LeaderState extends AbstractState {

	/* a map that stores for each server, index of the next log entry to send to
	    that server (initialized to leader last log index + 1). The data structure
	    is <node id, index of the next log entry>. Reinitialized after election */
	private Map<Integer, Integer> nextIndex;
	/* for each server, index of highest log entry known to be replicated on
	    server (initialized to 0, increases monotonically).  The data structure
	     is <node id, index of highest log entry...>. Reinitialized after election */
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
		// TODO: obtain the list of all nodes in the cluster (dummy placeholder below)
		List<NodeImpl> nodes = new ArrayList<>();

		// initialise nextIndex and matchIndex with default initial values
		for (NodeImpl remoteNode : nodes) {
			// skip self
			if (remoteNode == node)
				continue;

			int remoteId = remoteNode.getNodeId();
			nextIndex.put(remoteId, node.getRaftLog().getLastEntryIndex() + 1);
			matchIndex.put(remoteId, 0);
		}

		// TODO: setup periodic heartbeat
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// deny vote if requested from a stale candidate (term < currentTerm), the candidate's log is not up to date
		// (lastLogIndex < commitIndex) or the candidate is competing in the same election term (term == currentTerm);
		// this node has already won the election for currentTerm since it's the leader
		if (term <= currentTerm || lastLogIndex < commitIndex)
			return new VoteResponse(false, currentTerm);

		// otherwise, this node's term is out of date; revert to follower and grant vote
		node.setState(new FollowerState(node));
		// rectify term of this node based on candidate
		currentTerm = term;
		votedFor = candidateId;
		return new VoteResponse(true, currentTerm);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	public AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                                    int leaderCommit) {
		// revert to follower if this node's term is out of date
		if (term > currentTerm) {
			node.setState(new FollowerState(node));
			currentTerm = term;
		}
		// otherwise, deny the request
		return new AppendResponse(false, currentTerm);
	}
}
