import java.rmi.RemoteException;
import java.util.List;

public class CandidateState extends AbstractState {

	private final int MAJORITY_THRESHOLD;
	protected boolean isWaitingForVoteResponse;
	protected boolean haveVotedOtherNode;
	protected int runningRPC;

	public CandidateState(NodeImpl node) {
		super(node);
		// set majority threshold to ceil(cluster size / 2)
		this.MAJORITY_THRESHOLD = node.getRemoteNodes().size() / 2 + 1;
		this.isWaitingForVoteResponse = false;
		this.haveVotedOtherNode = false;
		this.runningRPC = 0;
	}

	/**
	 * Initialize attributes and setup a kind of
	 * timeout timer for election and start it
	 */
	public void start() throws InterruptedException, RemoteException {
		// TODO: initialize the candidate state

		while (true) {
			// Sleep for a random time.
			Thread.sleep(electionTimeout);

			if (haveVotedOtherNode) {
				// If I did vote any other node
				becomeFollower();
			}

			// Otherwise get all the remote nodes
			List<INode> remoteNodes = node.getRemoteNodes();
			// Request them to vote me
			requestOtherNodesToVoteMe(currentTerm, this.node.getNodeId(), this.node.getRaftLog().getLastEntryIndex(),
			                          this.node.getRaftLog().getLastCommittedIndex(), remoteNodes);

			// Increase the term num
			currentTerm += 1;
		}
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

		if (!isWaitingForVoteResponse) {
			// if I am not waiting my votes
			if (remoteTerm < currentTerm || remoteLastLogIndex < commitIndex) {
				// and if the remote caller has a smaller term or has an older log
				// reject to vote
				return new VoteResponse(false, currentTerm);
			} else {
				// otherwise  be ready to become a follower and vote the caller
				becomeFollower();
				return new VoteResponse(true, currentTerm);
			}
		} else {
			// if I am waiting my votes
			if (remoteTerm > currentTerm || remoteLastLogIndex > commitIndex) {
				// if the remote caller has a greater term or it has a news log
				// be ready to become a follower and vote the caller
				becomeFollower();
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
			becomeFollower();
			// return failed to let follower state worry about the log
		}
		return new AppendResponse(false, currentTerm);
	}

	/**
	 * Request other nodes to vote me
	 *
	 * @throws RemoteException
	 */
	private void requestOtherNodesToVoteMe(int myTerm, int myNodeID, int myLastLogIndex, int myLastLogTerm,
	                                       List<INode> remoteNodes) throws RemoteException {
		// Set my state to waiting my votes
		isWaitingForVoteResponse = true;
		// Store my votes count
		int myVotesCount = 0;
		// request them to vote me sync; might be async
		for (INode remoteNode : remoteNodes) {
			if (remoteNode.requestVote(myTerm, myNodeID, myLastLogIndex, myLastLogTerm).voteGranted) {
				// if the remote node votes me
				myVotesCount += 1;
				if (myVotesCount >= MAJORITY_THRESHOLD) {
					// if I got a majority of votes
					iGotAMajorityVotes();
					break;
				}
			}
		}
	}

	private void iGotAMajorityVotes() {
		// transfer to leaderState
		becomeLeader();
	}

	private void becomeLeader() {
		// TODO: Ensure the invoker returns in a right way and become a leader smoothly
	}

	private void becomeFollower() {
		// TODO: Ensure the invoker returns in a right way and become a follower smoothly
	}
}
