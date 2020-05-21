import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import sun.rmi.runtime.Log;

public class FollowerState extends AbstractState {
	// Use for remembering the last leader id
	private int currentLeaderId = -1;
	// Use for election timeout
	private ScheduledExecutorService scheduledExecutorService;
	private ScheduledFuture electionScheduleFuture;

	public FollowerState(NodeImpl node) {
		super(node);
	}

	/**
	 * Construct a Follower with a LeaderID
	 * @param node
	 * @param LeaderId
	 */
	public FollowerState(NodeImpl node, int LeaderId) {
		super(node);
		currentLeaderId = LeaderId;
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for heartbeat and start it
	 */
	public void start() {
		// Use for election timeout
		scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		// Init value
		votedFor = -1;
		currentLeaderId = -1;
		// Set the timer
		resetElectionTimer();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	@Override
	public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// Rules for all server
		resetElectionTimer();
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
		// What if term > currentTerm?
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	@Override
	public AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                                    int leaderCommit) {
		// Rules for all server
		resetElectionTimer();
		// When recover from a crash, we may have to set the leaderId.
		// So term equal to the currentTerm also need to update the leaderId.
		if (term >= currentTerm) {
			currentLeaderId = leaderId;
			setCurrentTerm(term);
		}
		// 1. Reply false if term < currentTerm (§5.1)
		if (term < currentTerm)
			return new AppendResponse(false, currentTerm);
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
			node.getRaftLog().writeEntries(prevLogIndex + 1, new ArrayList<LogEntry>(Arrays.asList(entries)));
			writePersistentState();
		} catch (RaftLog.MissingEntriesException e) {
			System.out.println("Entries missing: " + e);
		} catch (RaftLog.OverwriteCommittedEntryException e) {
			System.out.println("Overwrite Committed Entry is not allow: " + e);
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
	 * @return
	 */
	@Override
	public String handleCommand(String command, int timeout) {
		String res = null;
		try {
			INode leader = node.getRemoteNodes().get(currentLeaderId);
			res = ((IClientInterface)leader).sendCommand(command, timeout);
		} catch (IndexOutOfBoundsException e) {
			res = "No Such node with the ID: " + e;
		} catch (RemoteException e) {
			res = "Cannot reach the remote node: " + e;
		}
		return res;
	}

	/**
	 * Ser VoteFor and store it persistently.
	 * @param votedFor
	 */
	private void setVoteFor(int votedFor) {
		this.votedFor = votedFor;
		writePersistentState();
	}

	/**
	 * Ser currentTerm and store it persistently.
	 * @param term
	 * @return true if term > currentTerm
	 */
	private boolean setCurrentTerm(int term) {
		if (term > currentTerm) {
			this.votedFor = -1;
			currentTerm = term;
			writePersistentState();
			return true;
		}
		return false;
	}

	/**
	 * Set or reset the election timer, which would activate the becomeCandidate method when election timeout.
	 */
	private void resetElectionTimer() {
		if (electionScheduleFuture != null && !electionScheduleFuture.isDone()) {
			electionScheduleFuture.cancel(true);
		}
		electionScheduleFuture = scheduledExecutorService.schedule(new Runnable() {
			@Override
			public void run() {
				node.setState(new CandidateState(node));
			}
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}
}
