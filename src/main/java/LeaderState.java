import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.apache.log4j.Logger;

public class LeaderState extends AbstractState {
	static final Logger logger = Logger.getLogger(LeaderState.class.getName());

	private final int MAJORITY_THRESHOLD;
	/* a map that stores for each server, index of the next log entry to send to that server (initialized to leader last
	 * log index + 1). The data structure is <node id, index of the next log entry>. Reinitialized after election */
	private final Map<INode, Integer> nextIndex;
	/* for each server, index of highest log entry known to be replicated on server (initialized to 0, increases
	 * monotonically). The data structure is <node id, index of highest log entry...>. Reinitialized after election */
	private final Map<INode, Integer> matchIndex;
	/* a map of each remote node's active pending heartbeat */
	private final Map<INode, Heartbeat> activeHeartbeats;

	private static final int POLL_INTERVAL = 50;

	public LeaderState(NodeImpl node) {
		super(node);
		// set majority threshold to ceil((cluster size + 1) / 2)
		MAJORITY_THRESHOLD = (node.getRemoteNodes().size() + 1) / 2 + 1;
		nextIndex = new HashMap<>();
		matchIndex = new HashMap<>();
		activeHeartbeats = new HashMap<>();
	}

	/**
	 * Initialize attributes and setup a timer to send heartbeat message regularly
	 */
	public void start() {
		// initialise nextIndex and matchIndex with default initial values
		for (INode remoteNode : node.getRemoteNodes().values()) {
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

	@Override
	public String handleCommand(String command, int timeout) {
		LogEntry entry = node.getRaftLog().addNewEntry(currentTerm, command);
		writePersistentState();
		sendEarlyHeartbeats();

		for (int t = 0; t < timeout; t += POLL_INTERVAL) {
			if (entry.isCommitted())
				return "OK";
			try {
				Thread.sleep(POLL_INTERVAL);
			} catch (InterruptedException e) {
				return "Unsuccessful: interrupted";
			}
		}
		return "Timed out";
	}

	/**
	 * Send the initial heartbeat to each remote node.
	 * This method will also schedule the following heartbeat.
	 */
	private void initiateHeartbeats() {
		for (INode remoteNode : node.getRemoteNodes().values()) {
			int prevLogIndex = nextIndex.get(remoteNode) - 1;
			int prevLogTerm = node.getRaftLog().getTermOfEntry(prevLogIndex);
			LogEntry[] logEntries = {};
			int lastCommitted = node.getRaftLog().getLastCommittedIndex();

			Heartbeat heartbeat = new Heartbeat(remoteNode, 0, prevLogIndex, prevLogTerm, logEntries, lastCommitted);
			activeHeartbeats.put(remoteNode, heartbeat);
			CompletableFuture.supplyAsync(heartbeat).thenAccept(response -> scheduleNextHeartbeat(heartbeat, response));
		}
	}

	/**
	 * Schedule the next heartbeat.
	 * This method is to be invoked automatically (as part of a CompletableFuture) upon receiving a heartbeat response.
	 * @param heartbeat the heartbeat sent
	 * @param response  the response received (may be null)
	 */
	private void scheduleNextHeartbeat(Heartbeat heartbeat, AppendResponse response) {
		// stop if no longer leader or the heartbeat was not executed (another heartbeat sent before this was scheduled)
		if (node == null || !heartbeat.wasExecuted())
			return;

		// if the heartbeat was executed but a RemoteException occurred, immediately retry (same contents, no delay)
		if (response == null) {
			Heartbeat nextHeartbeat =
			    new Heartbeat(heartbeat.remoteNode, 0, heartbeat.prevLogIndex, heartbeat.prevLogTerm,
			                  heartbeat.logEntries, heartbeat.lastCommitted);
			activeHeartbeats.put(heartbeat.remoteNode, nextHeartbeat);
			CompletableFuture.supplyAsync(heartbeat).thenAccept(
			    newResponse -> scheduleNextHeartbeat(heartbeat, newResponse));
			return;
		}

		// a response was received; update remote node log info
		LogEntry latestEntrySent =
		    heartbeat.logEntries.length > 0 ? heartbeat.logEntries[heartbeat.logEntries.length - 1] : null;
		updateRemoteNodeInfo(heartbeat.remoteNode, latestEntrySent, response);
		checkReplication();

		// schedule next heartbeat with appropriate delay; this will be an empty heartbeat
		LogEntry[] logEntries = {};
		Heartbeat nextHeartbeat =
		    new Heartbeat(heartbeat.remoteNode, HEART_BEAT_INTERVAL, nextIndex.get(heartbeat.remoteNode) - 1,
		                  node.getRaftLog().getTermOfEntry(nextIndex.get(heartbeat.remoteNode) - 1), logEntries,
		                  node.getRaftLog().getLastCommittedIndex());
		activeHeartbeats.put(heartbeat.remoteNode, nextHeartbeat);
		CompletableFuture.supplyAsync(nextHeartbeat)
		    .thenAccept(newResponse -> scheduleNextHeartbeat(heartbeat, newResponse));
	}

	/**
	 * Deactivate all scheduled heartbeats and send a more up-to-date heartbeat to remote nodes instead.
	 */
	private void sendEarlyHeartbeats() {
		for (INode remoteNode : node.getRemoteNodes().values()) {
			Heartbeat scheduled = activeHeartbeats.get(remoteNode);
			if (scheduled != null)
				scheduled.deactivate();

			// construct and send updated heartbeat
			int prevLogIndex = nextIndex.get(remoteNode) - 1;
			int prevLogTerm = node.getRaftLog().getTermOfEntry(prevLogIndex);
			LogEntry[] logEntries;
			try {
				logEntries = node.getRaftLog()
				                 .getLogEntries()
				                 .subList(prevLogIndex, node.getRaftLog().getLastCommittedIndex())
				                 .toArray(new LogEntry[0]);
			} catch (IndexOutOfBoundsException e) {
				logEntries = new LogEntry[] {};
			}
			int lastCommitted = node.getRaftLog().getLastCommittedIndex();
			Heartbeat early = new Heartbeat(remoteNode, 0, prevLogIndex, prevLogTerm, logEntries, lastCommitted);
			activeHeartbeats.put(remoteNode, early);
			CompletableFuture.supplyAsync(early).thenAccept(response -> scheduleNextHeartbeat(early, response));
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
		for (int i = node.getRaftLog().getLastEntryIndex(); i > commitIndex; i--) {
			if (majorityHasLogEntry(i) && node.getRaftLog().getTermOfEntry(i) == currentTerm) {
				try {
					node.getRaftLog().commitToIndex(i);
					writePersistentState();
					commitIndex = i;
					sendEarlyHeartbeats();
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
		writePersistentState();
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

	/**
	 * Class to schedule and provide the ability to cancel a heartbeat.
	 */
	private class Heartbeat implements Supplier<AppendResponse> {
		/* the remote node to which this heartbeat is related */
		public final INode remoteNode;
		/* time to wait before sending this heartbeat */
		public final int delay;
		/* flag for whether this heartbeat is active (still scheduled to be sent) */
		private boolean active = true;
		/* flag to indicate whether this heartbeat has actually sent an RMI call */
		private boolean sent = false;

		/* arguments to provide to the remote node's appendEntries method */
		public final int prevLogIndex;
		public final int prevLogTerm;
		public final LogEntry[] logEntries;
		public final int lastCommitted;

		private Heartbeat(INode remoteNode, int delay, int prevLogIndex, int prevLogTerm, LogEntry[] logEntries,
		                  int lastCommitted) {
			this.remoteNode = remoteNode;
			this.delay = delay;
			this.prevLogIndex = prevLogIndex;
			this.prevLogTerm = prevLogTerm;
			this.logEntries = logEntries;
			this.lastCommitted = lastCommitted;
		}

		@Override
		public AppendResponse get() {
			try {
				// sleep until the heartbeat is scheduled to be sent
				Thread.sleep(delay);

				// upon waking up, check whether this heartbeat has deactivated in the meantime
				if (!isActive())
					return null;

				sent = true;
				return remoteNode.appendEntries(currentTerm, node.getNodeId(), prevLogIndex, prevLogTerm, logEntries,
				                                lastCommitted);

			} catch (InterruptedException | RemoteException e) {
				return null;
			}
		}

		/**
		 * @return true if this heartbeat is still active (will be sent when scheduled), else false
		 */
		private boolean isActive() {
			return active;
		}

		/**
		 * Void this heartbeat.
		 * This method is to be used when new log entries are received and a more up-to-date heartbeat is being sent
		 * before this heartbeat is scheduled.
		 */
		private void deactivate() {
			active = false;
			activeHeartbeats.remove(remoteNode);
		}

		/**
		 * Check whether this heartbeat actually executed, as the get method may return without sending an RMI call
		 * (would occur if this heartbeat was deactivated).
		 * @return true if this heartbeat was actually executed (sent an RMI call), else false.
		 * Note: this method returns true if a heartbeat was attempted to be sent but a RemoteException occurred.
		 */
		private boolean wasExecuted() {
			return sent;
		}
	}
}
