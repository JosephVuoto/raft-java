import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;
import org.apache.log4j.Logger;

public class FollowerState extends AbstractState {
	static final Logger logger = Logger.getLogger(FollowerState.class.getName());

	// Use for remembering the last leader id
	private int currentLeaderId = -1;
	// Use for election timeout
	private ScheduledExecutorService scheduledExecutorService;
	private ScheduledFuture electionScheduleFuture;

	public FollowerState(NodeImpl node) {
		super(node);
		init();
		currentLeaderId = -1;
	}

	/**
	 * Set back to follower with specific value
	 */
	public FollowerState(NodeImpl node, int voteFor, int leaderId) {
		super(node);
		init();
		this.setVoteFor(voteFor);
		this.currentLeaderId = leaderId;
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for heartbeat and start it
	 */
	public void start() {
		// Set the timer
		resetElectionTimer();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	@Override
	public synchronized VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		if (term > currentTerm)
			setCurrentTerm(term);
		// Reply false if term < currentTerm (§5.1)
		if (term < currentTerm)
			return new VoteResponse(false, currentTerm);
		// Grant vote conditions (§5.2, §5.4):
		// 	 1. votedFor is null or candidateId,
		// 	 2. candidate’s log is at least as up-to-date as receiver’s log.
		//      Up-to-date:
		//        1. logs have entries with later term
		//        2. logs end with same term and the longer one is more up to date.
		int currLastCommittedLogIndex = node.getRaftLog().getLastCommittedIndex();
		if ((votedFor == -1 || votedFor == candidateId) &&
		    (lastLogTerm > currLastCommittedLogIndex ||
		     (lastLogTerm == currLastCommittedLogIndex && lastLogIndex >= commitIndex))) {
			setCurrentTerm(term);
			setVoteFor(candidateId);
			return new VoteResponse(true, currentTerm);
		} else {
			return new VoteResponse(false, currentTerm);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	@Override
	public synchronized AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                                    int leaderCommit) {
		// 1. Reply false if term < currentTerm (§5.1)
		if (term < currentTerm)
			return new AppendResponse(false, currentTerm);
		// When recover from a crash, we may have to set the leaderId.
		// So term equal to the currentTerm also need to update the leaderId.
		resetElectionTimer();
		currentLeaderId = leaderId;
		setCurrentTerm(term);
		// 2. Reply false if log doesn’t contain an entry at prevLogIndex
		//    whose term matches prevLogTerm (§5.3)
		if (node.getRaftLog().getTermOfEntry(prevLogIndex) != prevLogTerm)
			return new AppendResponse(false, currentTerm);
		// 3. If an existing entry conflicts with a new one (same index
		//	  but different terms), delete the existing entry and all that
		//    follow it (§5.3)
		// 4. Append any new entries not already in the log
		try {
			// write to the next position, add 1.
			node.getRaftLog().writeEntries(prevLogIndex + 1, new ArrayList<>(Arrays.asList(entries)));
			writePersistentState();
		} catch (RaftLog.MissingEntriesException e) {
			logger.debug("node #" + node.getNodeId() + ": Entries missing");
		} catch (RaftLog.OverwriteCommittedEntryException e) {
			logger.debug("node #" + node.getNodeId() + ": Overwrite Committed Entry is not allow");
		}
		// 5. If leaderCommit > commitIndex, set commitIndex =
		// min(leaderCommit, index of last new entry)
		if (leaderCommit > commitIndex) {
			commitIndex = Math.min(leaderCommit, node.getRaftLog().getLastEntryIndex());
			// Once the follower learns that a log entry is committed,
			// it applies the entry to its local state machine(in log order).
			try {
				node.getRaftLog().commitToIndex(commitIndex);
			} catch (RaftLog.MissingEntriesException e) {
				// TODO: log
			}
		}
		return new AppendResponse(true, currentTerm);
	}

	/**
	 * Redirect the command to the leader and send back the result on behalf of the leader
	 * @param command Command string e.g. "set id 1"
	 * @param timeout in millisecond
	 */
	@Override
	public String handleCommand(String command, int timeout) {
		String res;
		try {
			INode leader = node.getRemoteNodes().get(currentLeaderId);
			if (leader == null) {
				res = "No Such node with the leader ID: "+ currentLeaderId;
			} else {
				res = ((IClientInterface)leader).sendCommand(command, timeout);
			}
		} catch (RemoteException e) {
			res = "Cannot reach the remote node: " + e;
		}
		return res;
	}

	/**
	 * initial function
	 */
	private void init() {
		// Use for election timeout
		scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
	}

	/**
	 * Ser VoteFor and store it persistently.
	 */
	private void setVoteFor(int votedFor) {
		AbstractState.votedFor = votedFor;
		writePersistentState();
	}

	/**
	 * Ser currentTerm and store it persistently.
	 */
	private void setCurrentTerm(int term) {
		if (term > currentTerm) {
			votedFor = -1;
			currentTerm = term;
			writePersistentState();
		}
	}

	/**
	 * Set or reset the election timer, which would activate the becomeCandidate method when election timeout.
	 */
	private void resetElectionTimer() {
		if (electionScheduleFuture != null && !electionScheduleFuture.isDone()) {
			electionScheduleFuture.cancel(true);
		}
		electionScheduleFuture = scheduledExecutorService.schedule(() -> {
			node.setState(new CandidateState(node));
			node = null;
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}
}
