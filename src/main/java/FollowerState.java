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
		writePersistentState();
		// Set the timer
		resetElectionTimer();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
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
	public AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
	                                    int leaderCommit) {

		// Rules for all server
		resetElectionTimer();
		// When recover from a crash, we may have to set the leaderId.
		// So term equal to the currentTerm also need to update the leaderId.
		if (term >= currentTerm)
			currentLeaderId = leaderId;
			setCurrentTerm(term);
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
			node.getRaftLog().writeEntries(prevLogIndex, new ArrayList<LogEntry>(Arrays.asList(entries)));
			writePersistentState();
		} catch (RaftLog.MissingEntriesException e) {
			System.out.println("Entries missing: " + e);
		} catch (RaftLog.OverwriteCommittedEntryException e) {
			System.out.println("Overwrite Committed Entry is not allow: " + e);
		}
		// 5. If leaderCommit > commitIndex, set commitIndex =
		// min(leaderCommit, index of last new entry)
		if (leaderCommit > commitIndex)
			commitIndex = Math.min(leaderCommit, node.getRaftLog().getLastEntryIndex());
		return new AppendResponse(true, currentTerm);
	}

	private void setVoteFor(int votedFor) {
		this.votedFor = votedFor;
		writePersistentState();
	}

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
	 * If a client contacts a follower, the follower redirects it to the leader
	 * @return
	 */
	private String send2Leader(String command) {
		// TODO: Currently I don't know when I would get a command;
		String res = "Fail to write the log";
		try {
			INode leader = node.getRemoteNodes().get(currentLeaderId);
			res = ((IClientInterface)leader).sendCommand(command);
		} catch (IndexOutOfBoundsException e) {
			System.out.println("No Such node with the ID: " + e);
		} catch (RemoteException e) {
			System.out.println("Cannot reach the remote node: " + e);
		}
		return res;
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
				// Become candidate if election timeout
				++currentTerm;
				node.setState(new CandidateState(node));
			}
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}
}
