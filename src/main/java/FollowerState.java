import sun.rmi.runtime.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;

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
	 * Initial with specific term. This may happen when a candidate return to FollowerState.
	 * @param node
	 * @param term a higher term is required.
	 */
	public FollowerState(NodeImpl node, int term) {
		super(node);
		if (currentTerm < term) currentTerm = term;
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for heartbeat and start it
	 */
	public void start() {
		scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
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
	public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// Rules for all server
		resetElectionTimer();
		if (term > currentTerm) changeTerm(term);
		// Reply false if term < currentTerm (§5.1)
		if (term < currentTerm) return new VoteResponse(false, currentTerm);
		// Grant vote conditions (§5.2, §5.4):
		// 	 1. votedFor is null or candidateId,
		// 	 2. candidate’s log is at least as up-to-date as receiver’s log.
		//      Up-to-date:
		//        1. logs have entries with later term
		//        2. logs end with same term and the longer one is more up to date.
		int currLastCommittedLogIndex = node.getRaftLog().getLastCommittedIndex();
		if ((votedFor == -1 || votedFor == candidateId) && (lastLogTerm > currLastCommittedLogIndex
				|| (lastLogTerm == currLastCommittedLogIndex && lastLogIndex >= commitIndex))) {
			changeTerm(term);
			votedFor = candidateId;
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
		currentLeaderId = leaderId;
		// Rules for all server
		resetElectionTimer();
		if (term > currentTerm) changeTerm(term);
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
		} catch (RaftLog.MissingEntriesException e) {
			System.out.println("Entries missing: " + e);
		} catch (RaftLog.OverwriteCommittedEntryException e) {
			System.out.println("Overwrite Committed Entry is not allow: " + e);
		}
		// 5. If leaderCommit > commitIndex, set commitIndex =
		// min(leaderCommit, index of last new entry)
		if (leaderCommit > commitIndex) commitIndex = Math.min(leaderCommit, node.getRaftLog().getLastEntryIndex());
		return new AppendResponse(true, currentTerm);
	}

	/**
	 * If a client contacts a follower, the follower redirects it to the leader
	 * @return
	 */
	private int redirect2Leader() {
		// TODO: not so sure when will a client interact with me.
		return currentLeaderId;
	}

	/**
	 * Use for term changing, which would reset the votedFor automatically (So I don't have to remember that).
	 * @param term
	 */
	private boolean changeTerm(int term) {
		if (term > currentTerm) {
			currentTerm = term;
			votedFor = -1;
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
				// Become candidate if election timeout
				++currentTerm;
				node.setState(new CandidateState(node));
			}
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}
}
