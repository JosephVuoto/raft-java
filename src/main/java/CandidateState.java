import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;
import org.apache.log4j.Logger;

public class CandidateState extends AbstractState {
	static final Logger logger = Logger.getLogger(CandidateState.class.getName());

	private final int MAJORITY_THRESHOLD;
	ScheduledExecutorService scheduledExecutorService;
	private ScheduledFuture electionScheduleFuture;

	protected boolean isWaitingForVoteResponse;
	protected int myVotes;


	public CandidateState(NodeImpl node) {
		super(node);
		MAJORITY_THRESHOLD = (node.getRemoteNodes().size() + 1) / 2 + 1;
		scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
	}

	public void start() {
		// Increment currentTerm
		setCurrentTerm(currentTerm + 1);
		// Vote for self
		setVoteFor(node.getNodeId());
		myVotes = 1;
		// Reset election timer
		resetElectionTimer();
		// Send RequestVote RPCs to all other servers
		sendRequestVote2All();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
		// Reply false if term < currentTerm (§5.1)
		if (term > currentTerm) {
			setCurrentTerm(term);
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
	public AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm, LogEntry[] entries,
										int leaderCommit){
		// Much of the thing is like the Follower
		// One different: If Append Entries received from new leader: convert to follower

		// Rules for all server
		resetElectionTimer();
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
				logger.error("Error from a candidate: " + e);
			}
		}
		if (term >= currentTerm) {
			// If Append Entries received from new leader: convert to follower
			setCurrentTerm(term);
			becomeFollower(-1, leaderId);
		}
		return new AppendResponse(true, currentTerm);
	}

	private void sendRequestVote2All() {
		int myLastLogIndex = this.node.getRaftLog().getLastEntryIndex();
		int myLastLogTerm = this.node.getRaftLog().getTermOfEntry(myLastLogIndex);
		int myID = node.getNodeId();
		for (INode remoteNode : node.getRemoteNodes().values()) {
			CompletableFuture
			    .supplyAsync(() -> {
				    try {
					    // sleep until the election time is scheduled to be sent
					    return remoteNode.requestVote(currentTerm, myID, myLastLogIndex, myLastLogTerm);
				    } catch (RemoteException e) {
					    return null;
					    // TODO
				    }
			    })
			    .thenAccept(this::handleVoteRes);
		}
	}

	private void handleVoteRes(VoteResponse voteResponse) {
		// stop if no longer candidate
		if (node == null)
			return;
		if (voteResponse == null) {
			// TODO
		}
		if (voteResponse.voteGranted) {
			myVotes += 1;
			if (myVotes >= MAJORITY_THRESHOLD) {
				becomeLeader();
			}
		}
	}

	/**
	 * Change to follower
	 * @param voteFor
	 * @param leaderId
	 */
	private void becomeFollower(int voteFor, int leaderId) {
		node.setState(new FollowerState(node, voteFor, leaderId));
		this.node = null;
	}

	/**
	 * Change to leader
	 */
	private void becomeLeader() {
		node.setState(new LeaderState(node));
		this.node = null;
	}

	@Override
	public String handleCommand(String command, int timeout) {
		return "Unsuccessful: interrupted";
	}

	/**
	 * If a client contacts a follower, the follower redirects it to the leader
	 *
	 * @return the res of the command sending
	 */
	private String sendToLeader(String command, int timeout) {
		String res = "Fail to write the log";
		try {
			INode leader = node.getRemoteNodes().get(votedFor);
			if (leader == null) {
				res = "No Such node with the leader ID: "+ votedFor;
			} else {
				res = ((IClientInterface)leader).sendCommand(command, timeout);
			}
		} catch (RemoteException e) {
			res = "Cannot reach the remote node: " + e;
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
				node.setState(new CandidateState(node));
			}
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}

	/**
	 * Set voted for
	 * @param newVotedFor
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
}
