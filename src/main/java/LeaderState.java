import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LeaderState extends AbstractState {
	private final int MAJORITY_THRESHOLD;
	/* a map that stores for each server, index of the next log entry to send to that server (initialized to leader last
	 * log index + 1). The data structure is <node id, index of the next log entry>. Reinitialized after election */
	private final Map<INode, Integer> nextIndex;
	/* for each server, index of highest log entry known to be replicated on server (initialized to 0, increases
	 * monotonically). The data structure is <node id, index of highest log entry...>. Reinitialized after election */
	private final Map<INode, Integer> matchIndex;

	public LeaderState(NodeImpl node) {
		super(node);
		// set majority threshold to ceil(cluster size / 2)
		MAJORITY_THRESHOLD = node.getRemoteNodes().size() / 2 + 1;
		nextIndex = new HashMap<>();
		matchIndex = new HashMap<>();
	}

	/**
	 * Initialize attributes and setup a timer to send heartbeat message regularly
	 */
	public void start() {
		// initialise nextIndex and matchIndex with default initial values
		for (INode remoteNode : node.getRemoteNodes()) {
			nextIndex.put(remoteNode, node.getRaftLog().getLastEntryIndex() + 1);
			matchIndex.put(remoteNode, 0);
		}

		initiateHeartbeats();
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
		resign(term, candidateId);
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
		if (term > currentTerm)
			resign(term);

		// otherwise, deny the request
		return new AppendResponse(false, currentTerm);
	}

	private void initiateHeartbeats() {
		for (INode remoteNode : node.getRemoteNodes()) {
			int prevLogIndex = nextIndex.get(remoteNode) - 1;
			int prevLogTerm = node.getRaftLog().getTermOfEntry(prevLogIndex);
			LogEntry[] logEntries = {};
			int lastCommitted = node.getRaftLog().getLastCommittedIndex();

			CompletableFuture
			    .supplyAsync(() -> {
				    try {
					    return remoteNode.appendEntries(currentTerm, node.getNodeId(), prevLogIndex, prevLogTerm,
					                                    logEntries, lastCommitted);
				    } catch (RemoteException e) {
					    return null;
				    }
			    })
			    .thenAccept(response -> {
				    // stop if no longer leader
				    if (node == null)
					    return;

				    updateRemoteNodeInfo(remoteNode, null, response);
				    checkReplication();
				    // TODO: schedule the next heartbeat
			    });
		}
	}

	/**
	 * Update the information known about a remote node's log.
	 * This method is invoked as part of a CompletableFuture upon receiving the result of an appendEntries RMI call.
	 * @param remoteNode      the node upon which the appendEntries RMI call was called
	 * @param latestEntrySent the latest entry sent to the remote node
	 * @param response        the result of the RMI call
	 */
	private void updateRemoteNodeInfo(INode remoteNode, LogEntry latestEntrySent, AppendResponse response) {
		if (response == null)
			return;

		// revert to follower if response indicates a higher term
		if (response.term > currentTerm) {
			resign(response.term);
			return;
		}

		// if the append was successful, update the highest log entry known to be replicated
		if (response.success) {
			// empty heartbeat case
			if (latestEntrySent == null)
				return;

			// non-empty heartbeat case
			matchIndex.put(remoteNode, latestEntrySent.index);
			nextIndex.put(remoteNode, latestEntrySent.index + 1);
			return;
		}

		// otherwise, send one more previous log next time
		int revisedNextIndex = Integer.max(nextIndex.get(remoteNode) - 1, 0);
		nextIndex.put(remoteNode, revisedNextIndex);
	}

	/**
	 * Commit entries which have been replicated by a majority.
	 * Note: only commit if a majority of nodes has an entry from the current term.
	 */
	private void checkReplication() {
		for (int i = node.getRaftLog().getLastCommittedIndex(); i > commitIndex; i--) {
			if (majorityHasLogEntry(i) && node.getRaftLog().getTermOfEntry(i) == currentTerm) {
				try {
					node.getRaftLog().commitToIndex(i);
				} catch (RaftLog.MissingEntriesException e) {
					// TODO: logging (execution should never reach here)
					return;
				}
				return;
			}
		}
	}

	/**
	 * Determine whether a log entry has been replicated on a majority of nodes.
	 * @param i index of the log entry to be checked
	 * @return true if a majority has replicated the log entry, else false
	 */
	private boolean majorityHasLogEntry(int i) {
		int replicatedOn = 0;
		for (Map.Entry<INode, Integer> entry : matchIndex.entrySet())
			if (entry.getValue() >= i && ++replicatedOn >= MAJORITY_THRESHOLD)
				return true;
		return false;
	}

	/**
	 * Perform cleanup actions when stepping down from leader.
	 * @param supersedingTerm the higher term received from a remote node
	 */
	private void resign(int supersedingTerm) {
		node.setState(new FollowerState(node));
		currentTerm = supersedingTerm;
		// indicate no longer being leader; this reference is checked before scheduling the next heartbeat
		node = null;
	}

	/**
	 * Perform cleanup actions when stepping down from leader, having voted for a candidate in a superseding term.
	 * @param supersedingTerm the higher term received from a remote node
	 * @param exitVote        the candidate granted a vote by this node
	 */
	private void resign(int supersedingTerm, int exitVote) {
		resign(supersedingTerm);
		votedFor = exitVote;
	}
}
