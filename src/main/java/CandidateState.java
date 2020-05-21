import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;
import org.apache.log4j.Logger;

public class CandidateState extends AbstractState {
	static final Logger logger = Logger.getLogger(CandidateState.class.getName());

	private final int MAJORITY_THRESHOLD;
	protected boolean isWaitingForVoteResponse;
	protected int myVotes;

	public CandidateState(NodeImpl node) {
		super(node);
		MAJORITY_THRESHOLD = (node.getRemoteNodes().size() + 1) / 2 + 1;
		isWaitingForVoteResponse = false;
		myVotes = 1;
	}

	public void start() {
		scheduleAnotherRound();
		requestAllRemoteNodesToVote();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @see AbstractState#requestVote(int, int, int, int)
	 */
	public VoteResponse requestVote(int remoteTerm, int remoteCandidateId, int remoteLastLogIndex,
	                                int remoteLastLogTerm) {
		if (!isWaitingForVoteResponse) {
			// if I am not waiting my votes
			if (remoteTerm < currentTerm || remoteLastLogIndex < commitIndex) {
				// and if the remote caller has a smaller term or has an older log
				// reject to vote
				return new VoteResponse(false, currentTerm);
			} else {
				// otherwise  be ready to become a follower and vote the caller
				becomeFollower(remoteTerm, remoteCandidateId);
				return new VoteResponse(true, currentTerm);
			}
		} else {
			// if I am waiting my votes
			if (remoteTerm > currentTerm || remoteLastLogIndex > commitIndex) {
				// if the remote caller has a greater term or it has a news log
				// be ready to become a follower and vote the caller
				becomeFollower(remoteTerm, remoteCandidateId);
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

		int myLastLogIndex = this.node.getRaftLog().getLastEntryIndex();
		int myLastLogTerm = this.node.getRaftLog().getTermOfEntry(myLastLogIndex);
		// WIP: confusing part, need to discuss
		if (remoteTerm >= currentTerm || remotePrevLogTerm >= myLastLogTerm || remotePrevLogIndex >= myLastLogIndex) {
			// if remote caller has a greater term and last log entry
			becomeFollower(remoteTerm, remoteLeaderId);
		}
		return doAppendEntries(remoteTerm, remotePrevLogIndex, remotePrevLogTerm, remoteEntries, remoteLeaderCommit);
	}

	private AppendResponse doAppendEntries(int remoteTerm, int remotePrevLogIndex, int remotePrevLogTerm,
	                                       LogEntry[] remoteEntries, int remoteLeaderCommit) {

		// 1. Reply false if term < currentTerm (§5.1)
		if (remoteTerm < currentTerm) {
			node = null;
			return new AppendResponse(false, currentTerm);
		}
		// 2. Reply false if log doesn’t contain an entry at prevLogIndex
		//    whose term matches prevLogTerm (§5.3)
		if (node.getRaftLog().getTermOfEntry(remotePrevLogIndex) != remotePrevLogTerm) {
			return new AppendResponse(false, currentTerm);
		}
		// 3. If an existing entry conflicts with a new one (same index
		//	  but different terms), delete the existing entry and all that
		//    follow it (§5.3)
		// 4. Append any new entries not already in the log
		try {
			node.getRaftLog().writeEntries(remotePrevLogIndex, new ArrayList<>(Arrays.asList(remoteEntries)));
			writePersistentState();
		} catch (RaftLog.MissingEntriesException e) {
			logger.debug("node #" + node.getNodeId() + ": Entries missing");
		} catch (RaftLog.OverwriteCommittedEntryException e) {
			logger.debug("node #" + node.getNodeId() + ": Overwrite Committed Entry is not allow");
		}
		// 5. If leaderCommit > commitIndex, set commitIndex =
		// min(leaderCommit, index of last new entry)
		if (remoteLeaderCommit > commitIndex) {
			commitIndex = Math.min(remoteLeaderCommit, node.getRaftLog().getLastEntryIndex());
			try {
				node.getRaftLog().commitToIndex(commitIndex);
			} catch (RaftLog.MissingEntriesException e) {
				e.printStackTrace();
			}
		}
		node = null;
		return new AppendResponse(true, currentTerm);
	}

	private void requestAllRemoteNodesToVote() {

		int myLastLogIndex = this.node.getRaftLog().getLastEntryIndex();
		int myLastLogTerm = this.node.getRaftLog().getTermOfEntry(myLastLogIndex);
		int myID = node.getNodeId();
		for (Integer remoteId : node.getRemoteNodes().keySet()) {
			CompletableFuture
			    .supplyAsync(() -> {
				    try {
					    // sleep until the election time is scheduled to be sent
					    INode remoteNode = node.getRemoteNodes().get(remoteId);
					    return remoteNode.requestVote(currentTerm, myID, myLastLogIndex, myLastLogTerm);

				    } catch (RemoteException e) {
					    /* Connection lost, reconnect... */
					    String remoteUrl = node.getRemoteUrl(remoteId);
					    try {
						    INode newRemoteNode = (INode)Naming.lookup(remoteUrl);
						    node.updateRemoteNode(remoteId, newRemoteNode);
					    } catch (NotBoundException | MalformedURLException | RemoteException notBoundException) {
						    // TODO: ignore
					    }
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
		ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
		scheduledExecutorService.schedule(() -> {
			if (node != null) {
				currentTerm += 1;
				node.setState(new CandidateState(node));
				node = null;
			}
		}, electionTimeout, TimeUnit.MILLISECONDS);
	}

	private void becomeLeader() {
		setVoteFor(this.node.getNodeId());
		node.setState(new LeaderState(node));
		this.node = null;
	}

	private void becomeFollower(int remoteTerm, int iVotedFor) {
		setCurrentTerm(remoteTerm);

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
	 * @return the res of the command sending
	 */
	private String sendToLeader(String command, int timeout) {
		//		String res = "Fail to write the log";
		//		try {
		//			INode leader = node.getRemoteNodes().get(votedFor);
		//			res = ((IClientInterface)leader).sendCommand(command, timeout);
		//		} catch (IndexOutOfBoundsException e) {
		//			System.out.println("No Such node with the ID: " + e);
		//		} catch (RemoteException e) {
		//			System.out.println("Cannot reach the remote node: " + e);
		//		}
		//		return res;
		String res = "Fail to write the log";
		try {
			INode leader = node.getRemoteNodes().get(votedFor);
			if (leader == null) {
				res = "No Such node with the leader ID: " + votedFor;
			} else {
				res = ((IClientInterface)leader).sendCommand(command, timeout);
			}
		} catch (RemoteException e) {
			res = "Cannot reach the remote node: " + e;
		}
		return res;
	}

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
