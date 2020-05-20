import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class CandidateState extends AbstractState {

	private final int MAJORITY_THRESHOLD;
	protected boolean isWaitingForVoteResponse;
	protected int myVotes;

	private ScheduledExecutorService scheduledExecutorService;

	private final Map<INode, Integer> nextIndex;
	/* for each server, index of highest log entry known to be replicated on server (initialized to 0, increases
	 * monotonically). The data structure is <node id, index of highest log entry...>. Reinitialized after election */
	private final Map<INode, Integer> matchIndex;

	public CandidateState(NodeImpl node) {
		super(node);
		// set majority threshold
		MAJORITY_THRESHOLD = (node.getRemoteNodes().size() + 1) / 2 + 1;
		isWaitingForVoteResponse = false;
		myVotes = 0;
		nextIndex = new HashMap<>();
		matchIndex = new HashMap<>();
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for election and start it
	 */
	public void start() {
		// TODO: initialize the candidate state
		scheduleAnotherRound();

		for (INode remoteNode : node.getRemoteNodes()) {
			nextIndex.put(remoteNode, node.getRaftLog().getLastEntryIndex() + 1);
			matchIndex.put(remoteNode, 0);
		}

		requestAllRemoteNodesToVote();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public VoteResponse requestVote(int remoteTerm, int remoteCandidateId, int remoteLastLogIndex,
	                                int remoteLastLogTerm) {
		// TODO: determine whether to vote for the caller. if yes, then the node should
		// go back to follower state, if not, return the current term number.

		if (haveAMajorityVotes || haveVotedOtherNode) {
			// if I am in a transform progress
			return new VoteResponse(false, currentTerm);
		}

		if (!isWaitingForVoteResponse) {
			// if I am not waiting my votes
			if (remoteTerm < currentTerm || remoteLastLogIndex < commitIndex) {
				// and if the remote caller has a smaller term or has an older log
				// reject to vote
				return new VoteResponse(false, currentTerm);
			} else {
				// otherwise  be ready to become a follower and vote the caller
				currentTerm = remoteTerm;
				becomeFollower(remoteCandidateId);
				return new VoteResponse(true, currentTerm);
			}
		} else {
			// if I am waiting my votes
			if (remoteTerm > currentTerm || remoteLastLogIndex > commitIndex) {
				// if the remote caller has a greater term or it has a news log
				// be ready to become a follower and vote the caller
				currentTerm = remoteTerm;
				becomeFollower(remoteCandidateId);
				return new VoteResponse(true, currentTerm);
			} else {
				// otherwise reject
				return new VoteResponse(false, currentTerm);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#appendEntries(int, int, int, int, LogEntry[], int)
	 */
	public AppendResponse appendEntries(int remoteTerm, int remoteLeaderId, int remotePrevLogIndex,
	                                    int remotePrevLogTerm, LogEntry[] remoteEntries, int remoteLeaderCommit) {
		// TODO: if receive this RPC with a higher term number, then go back to follower
		// state. if not, return the current term number.

		int myLastLogIndex = this.node.getRaftLog().getLastEntryIndex();
		int myLastLogTerm = this.node.getRaftLog().getTermOfEntry(myLastLogIndex);
		// WIP: confusing part, need to discuss
		if (remoteTerm >= currentTerm || remotePrevLogTerm >= myLastLogTerm || remotePrevLogIndex >= myLastLogIndex) {
			// if remote caller has a greater term and last log entry
			currentTerm = remoteTerm;
			becomeFollower(remoteLeaderId);
		}
		return doAppendEntries(remoteTerm, remoteLeaderId, remotePrevLogIndex, remotePrevLogTerm, remoteEntries,
		                       remoteLeaderCommit);
	}

	private AppendResponse doAppendEntries(int remoteTerm, int remoteLeaderId, int remotePrevLogIndex,
	                                       int remotePrevLogTerm, LogEntry[] remoteEntries, int remoteLeaderCommit) {

		// Update term
		currentTerm = remoteTerm;

		// 1. Reply false if term < currentTerm (§5.1)
		if (remoteTerm < currentTerm)
			return new AppendResponse(false, currentTerm);
		// 2. Reply false if log doesn’t contain an entry at prevLogIndex
		//    whose term matches prevLogTerm (§5.3)
		if (node.getRaftLog().getTermOfEntry(remotePrevLogIndex) != remotePrevLogTerm)
			return new AppendResponse(false, currentTerm);
		// 3. If an existing entry conflicts with a new one (same index
		//	  but different terms), delete the existing entry and all that
		//    follow it (§5.3)
		// 4. Append any new entries not already in the log
		try {
			node.getRaftLog().writeEntries(remotePrevLogIndex, new ArrayList<LogEntry>(Arrays.asList(remoteEntries)));
		} catch (RaftLog.MissingEntriesException e) {
			System.out.println("Entries missing: " + e);
		} catch (RaftLog.OverwriteCommittedEntryException e) {
			System.out.println("Overwrite Committed Entry is not allow: " + e);
		}
		// 5. If leaderCommit > commitIndex, set commitIndex =
		// min(leaderCommit, index of last new entry)
		if (remoteLeaderCommit > commitIndex)
			commitIndex = Math.min(remoteLeaderCommit, node.getRaftLog().getLastEntryIndex());
		return new AppendResponse(true, currentTerm);
	}

	private void requestAllRemoteNodesToVote() {

		int myLastLogIndex = this.node.getRaftLog().getLastEntryIndex();
		int myLastLogTerm = this.node.getRaftLog().getTermOfEntry(myLastLogIndex);
		int myID = node.getNodeId();
		for (INode remoteNode : node.getRemoteNodes()) {
			CompletableFuture
			    .supplyAsync(() -> {
				    try {
					    // sleep until the election time is scheduled to be sent

					    return remoteNode.requestVote(currentTerm, myID, myLastLogIndex, myLastLogTerm);

				    } catch (RemoteException e) {
					    return new VoteResponse(false, -1);
				    }
			    })
			    .thenAccept(this::handleVoteRes);
		}
	}

	private void handleVoteRes(VoteResponse voteResponse) {
		// stop if no longer candidate
		if (node == null)
			return;

		if (voteResponse.voteGranted) {
			myVotes += 1;
			if (myVotes >= MAJORITY_THRESHOLD) {
				becomeLeader();
			}
		}
	}

	private void scheduleAnotherRound() {
		scheduledExecutorService.schedule(() -> {
			if (node != null) {
				currentTerm += 1;
				node.setState(new CandidateState(node));
				node = null;
			}
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}

	private void becomeLeader() {
		votedFor = this.node.getNodeId();
		node.setState(new LeaderState(node));
		this.node = null;
	}

	private void becomeFollower(int iVotedFor) {
		node.setState(new FollowerState(node, iVotedFor));
		this.node = null;
	}

	@Override
	public String handleCommand(String command, int timeout) {
		return sendToLeader(command, timeout);
	}

	/**
	 * If a client contacts a follower, the follower redirects it to the leader
	 *
	 * @return
	 */
	private String sendToLeader(String command, int timeout) {
		// TODO: Currently I don't know when I would get a command;
		String res = "Fail to write the log";
		try {
			INode leader = node.getRemoteNodes().get(votedFor);
			res = ((IClientInterface)leader).sendCommand(command, timeout);
		} catch (IndexOutOfBoundsException e) {
			System.out.println("No Such node with the ID: " + e);
		} catch (RemoteException e) {
			System.out.println("Cannot reach the remote node: " + e);
		}
		return res;
	}
}
