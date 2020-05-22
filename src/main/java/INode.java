import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Definition of the remote interface
 */
public interface INode extends Remote {
	/**
	 * Invoked by candidates to gather votes
	 *
	 * @param term         candidate’s term
	 * @param candidateId  candidate requesting vote
	 * @param lastLogIndex index of candidate’s last log entry
	 * @param lastLogTerm  term of candidate’s last log entry
	 * @return if follower votes for the candidate, returns 0;
	 * if rejects the vote, returns follower's currentTerm,
	 * for candidate to update itself
	 * @throws RemoteException communication-related exceptions that may
	 *                         occur during the execution of a remote
	 *                         method call.
	 */
	AbstractState.VoteResponse requestVote(int term, int candidateId, int lastLogIndex, int lastLogTerm)
	    throws RemoteException;

	/**
	 * Invoked by leader to replicate log entries; also used as heartbeat.
	 *
	 * @param term         leader’s term
	 * @param leaderId     so follower can redirect clients
	 * @param prevLogIndex index of log entry immediately preceding new ones
	 * @param prevLogTerm  term of prevLogIndex entry
	 * @param entries      log entries to store (empty for heartbeat;
	 *                     may send more than one for efficiency)
	 * @param leaderCommit leader’s commitIndex
	 * @return if follower accepts the request, return 0. If rejects the request,
	 * returns follower's currentTerm, for leader to update itself
	 * @throws RemoteException communication-related exceptions that may
	 *                         occur during the execution of a remote
	 *                         method call.
	 */
	AbstractState.AppendResponse appendEntries(int term, int leaderId, int prevLogIndex, int prevLogTerm,
	                                           LogEntry[] entries, int leaderCommit) throws RemoteException;
}
