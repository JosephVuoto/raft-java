import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.apache.log4j.Logger;

public class LeaderState extends AbstractState {
	static final Logger logger = Logger.getLogger(LeaderState.class.getName());

	private final int MAJORITY_THRESHOLD;
	/* a map that stores for each server, index of the next log entry to send to that server (initialized to leader last
	 * log index + 1). The data structure is <node id, index of the next log entry>. Reinitialized after election */
	private final ConcurrentHashMap<INode, Integer> nextIndex;
	/* for each server, index of highest log entry known to be replicated on server (initialized to 0, increases
	 * monotonically). The data structure is <node id, index of highest log entry...>. Reinitialized after election */
	private final ConcurrentHashMap<INode, Integer> matchIndex;
	/* Use for periodical heartbeat */
	private ScheduledExecutorService scheduledExecutorService;
	private ScheduledFuture heartbeatScheduledFuture;
	private ExecutorService executorService;

	private static final int POLL_INTERVAL = 50;

	public LeaderState(NodeImpl node) {
		super(node);
		// set majority threshold to ceil((cluster size + 1) / 2)
		MAJORITY_THRESHOLD = (node.getRemoteNodes().size() + 1) / 2 + 1;
		nextIndex = new ConcurrentHashMap<>();
		matchIndex = new ConcurrentHashMap<>();
		// Init thread pool
		executorService = new ThreadPoolExecutor(
				node.getRemoteNodes().size(),
				node.getRemoteNodes().size(),
				0,
				TimeUnit.SECONDS,
				new LinkedBlockingQueue<>());
		// Set to 2 because we may need to use it to handle snapshot later
		scheduledExecutorService = Executors.newScheduledThreadPool(2);
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
        startNewHeartbeat();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public synchronized VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// Already vote for itself in the current term, do not have to consider up to date because
		// Up to date would only be consider when term == current term.
		// Reply false if term < currentTerm (§5.1)
		if (term > currentTerm) {
			setCurrentTerm(term);
            setVoteFor(candidateId);
			becomeFollower(candidateId, -1);
			return new VoteResponse(true, term);
		}
		return new VoteResponse(false, term);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	public synchronized AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
	                                                 LogEntry[] entries, int leaderCommit) {
		// Much of the thing is like the Follower
		// One different: If Append Entries received from new leader: convert to follower
		// 1. Reply false if term < currentTerm (§5.1)
		if (term <= currentTerm)
			return new AppendResponse(false, currentTerm);
		// If Append Entries received from new leader: convert to f
		// Need to finish all the jobs before going back to a follower
		setCurrentTerm(term);
		return becomeFollower(-1, leaderId)
				.appendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
	}

	@Override
	public String handleCommand(String command, int timeout) {
		LogEntry entry = node.getRaftLog().addNewEntry(currentTerm, command);
		writePersistentState();
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
	 * Commit entries which have been replicated by a majority.
	 * Note: only commit if a majority of nodes has an entry from the current term.
	 */
	private void checkReplication() {
		// If there exists an N such that N > commitIndex, a majority of matchIndex[i] > N,
		// and log[N].term == currentTerm: set commitIndex = N;
		for (int i = node.getRaftLog().getLastEntryIndex(); i > commitIndex; i--) {
			if (majorityHasLogEntry(i) && node.getRaftLog().getTermOfEntry(i) == currentTerm) {
				try {
					node.getRaftLog().commitToIndex(i);
					writePersistentState();
					commitIndex = i;
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
		int replicatedOn = 1;
		for (int index : matchIndex.values())
			if (index >= i && ++replicatedOn >= MAJORITY_THRESHOLD)
				return true;
		return false;
	}

	/**
	 * Change to follower
	 */
	private FollowerState becomeFollower(int voteFor, int leaderId) {
        // stop heartbeat
        if (heartbeatScheduledFuture != null && !heartbeatScheduledFuture.isDone()) {
            heartbeatScheduledFuture.cancel(true);
        }
		FollowerState followerState = new FollowerState(node, voteFor, leaderId);
		node.setState(followerState);
		this.node = null;
		return followerState;
	}

	/**
	 * Set voted for
	 */
	private void setVoteFor(int newVotedFor) {
		votedFor = newVotedFor;
		writePersistentState();
	}

	/**
	 * Ser currentTerm and store it persistently.
	 *
	 * @param term term num
	 */
	private void setCurrentTerm(int term) {
		if (term > currentTerm) {
			votedFor = -1;
			currentTerm = term;
			writePersistentState();
		}
	}

	/**
	 * heartbeat timer, append entries
	 */
	private void resetHeartbeatTimer() {
		if (heartbeatScheduledFuture != null && !heartbeatScheduledFuture.isDone()) {
			heartbeatScheduledFuture.cancel(true);
		}
		heartbeatScheduledFuture = scheduledExecutorService.schedule(this::startNewHeartbeat, HEART_BEAT_INTERVAL, TimeUnit.MILLISECONDS);
	}

    /**
     * start new heartbeat
     */
    private void startNewHeartbeat() {
        for (INode remoteNode : node.getRemoteNodes().values()) {
            executorService.submit(() -> sendAppendEntries(remoteNode));
        }
        resetHeartbeatTimer();
    }

    /**
     * Use the same method to send both heartbeat and appendEntries
     */
    private void sendAppendEntries(INode remoteNode) {
        // construct and send updated heartbeat
        int prevLogIndex = nextIndex.get(remoteNode) - 1;
        int prevLogTerm = node.getRaftLog().getTermOfEntry(prevLogIndex);
        LogEntry[] logEntries;
        try {
            // Get the logEntries start from the nextIndex
            logEntries = node.getRaftLog()
                    .getLogEntries()
                    .subList(prevLogIndex, node.getRaftLog().getLastEntryIndex())
                    .toArray(new LogEntry[0]);
        } catch (IndexOutOfBoundsException e) {
            logEntries = new LogEntry[] {};
        }
        int lastCommitted = node.getRaftLog().getLastCommittedIndex();
        try {
        	logger.info("Send AE to Node ID: " + remoteNode.getRemoteNodeId());
        	logger.info(" Term: " + currentTerm + " PLIndex: " + prevLogIndex + " PLTerm: " + prevLogTerm + " lastCommitted: " + lastCommitted);
        	logger.info(" logEntries size: " + logEntries.length);
            AppendResponse appendResponse = remoteNode.appendEntries(currentTerm, node.getNodeId(),
                    prevLogIndex, prevLogTerm, logEntries, lastCommitted);
            // a response was received; update remote node log info
            LogEntry latestEntrySent = logEntries.length > 0 ? logEntries[logEntries.length - 1] : null;
            updateRemoteNodeInfo(remoteNode, latestEntrySent, appendResponse);
			checkReplication();
        } catch (RemoteException e) {
            logger.debug("Can not connect to remoteNode");
            // Do nothing and try again
        }
    }

    /**
     * Update the information known about a remote node's log.
     * This method is invoked as part of a CompletableFuture upon receiving the result of an appendEntries RMI call.
     * @param remoteNode      the node upon which the appendEntries RMI call was called
     * @param latestEntrySent the latest entry sent to the remote node
     * @param response        the result of the RMI call
     */
    private synchronized void updateRemoteNodeInfo(INode remoteNode, LogEntry latestEntrySent, AppendResponse response) {
        // Need to check again. If not synchronize, can't update some node's next index. by Aaron
        if (response == null)
            return;
        // revert to follower if response indicates a higher term
        if (response.term > currentTerm) {
            becomeFollower(-1, -1);
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

    //TODO: If commitIndex > lastApplied: increment lastApplied, apply log[lastApplied] to state machine.

}
