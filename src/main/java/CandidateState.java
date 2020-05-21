import java.rmi.RemoteException;
import java.util.concurrent.*;
import org.apache.log4j.Logger;

public class CandidateState extends AbstractState {
	static final Logger logger = Logger.getLogger(CandidateState.class.getName());

	private final int MAJORITY_THRESHOLD;
	ScheduledExecutorService scheduledExecutorService;
	private ScheduledFuture electionScheduleFuture;
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
	public synchronized VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm) {
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
	public synchronized AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
	                                                 LogEntry[] entries, int leaderCommit) {
		// Much of the thing is like the Follower
		// One different: If Append Entries received from new leader: convert to follower
		// 1. Reply false if term < currentTerm (§5.1)
		if (term < currentTerm)
			return new AppendResponse(false, currentTerm);
		// If Append Entries received from new leader: convert to follower
		// Need to finish all the jobs before going back to a follower
		setCurrentTerm(term);
		return becomeFollower(-1, leaderId)
		    .appendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
	}

	private void sendRequestVote2All() {
		int myLastLogIndex = this.node.getRaftLog().getLastEntryIndex();
		int myLastLogTerm = this.node.getRaftLog().getTermOfEntry(myLastLogIndex);
		int myID = node.getNodeId();
		for (INode remoteNode : node.getRemoteNodes().values()) {
			CompletableFuture
			    .supplyAsync(() -> {
				    try {
					    return remoteNode.requestVote(currentTerm, myID, myLastLogIndex, myLastLogTerm);
				    } catch (RemoteException e) {
					    return null;
				    }
			    })
			    .thenAccept(this::handleVoteRes);
		}
	}

	private synchronized void handleVoteRes(VoteResponse voteResponse) {
		// stop if no longer candidate
		if (node == null || voteResponse == null)
			return;

		if (voteResponse.voteGranted) {
			myVotes += 1;
			if (myVotes >= MAJORITY_THRESHOLD) {
				becomeLeader();
			}
		}
	}

	/**
	 * Change to follower
	 */
	private FollowerState becomeFollower(int voteFor, int leaderId) {
		electionScheduleFuture.cancel(true);
		FollowerState followerState = new FollowerState(node, voteFor, leaderId);
		node.setState(followerState);
		this.node = null;
		return followerState;
	}

	/**
	 * Change to leader
	 */
	private void becomeLeader() {
		electionScheduleFuture.cancel(true);
		node.setState(new LeaderState(node));
		this.node = null;
	}

	@Override
	public String handleCommand(String command, int timeout) {
		return "Unsuccessful: interrupted";
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
}
