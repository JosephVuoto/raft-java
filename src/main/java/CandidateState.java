import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

public class CandidateState extends AbstractState {

    private final int MAJORITY_THRESHOLD;
    protected boolean isWaitingForVoteResponse;
    protected boolean haveVotedOtherNode;
    protected boolean haveAMajorityVotes;

    private ScheduledExecutorService scheduledExecutorService;
    private ScheduledFuture electionScheduleFuture;

    public CandidateState(NodeImpl node) {
        super(node);
        // set majority threshold
        this.MAJORITY_THRESHOLD = (node.getRemoteNodes().size() + 1) / 2 + 1;
        this.isWaitingForVoteResponse = false;
        this.haveVotedOtherNode = false;
        this.haveAMajorityVotes = false;
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
                becomeFollower(votedFor);
            }
            if (haveAMajorityVotes) {
                // If I already had a majority votes
                becomeLeader();
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
        // Rules for all server
        resetElectionTimer();

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

                if (haveVotedOtherNode) {
                    // if I am in a transform progress to become a follower
                    break;
                }

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
        votedFor = this.node.getNodeId();
        node.setState(new LeaderState(node));
    }

    private void becomeFollower(int iVotedFor) {
        votedFor = iVotedFor;
        node.setState(new FollowerState(node));
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
