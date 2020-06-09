import java.util.List;

/**
 * Persistent state on all servers. Updated on stable storage before responding to RPCs
 */
public class PersistentState {

	private int currentTerm;
	private int voteFor;
	private List<LogEntry> logEntries;

	public int getCurrentTerm() {
		return currentTerm;
	}

	public void setCurrentTerm(int currentTerm) {
		this.currentTerm = currentTerm;
	}

	public int getVoteFor() {
		return voteFor;
	}

	public void setVoteFor(int voteFor) {
		this.voteFor = voteFor;
	}

	public List<LogEntry> getLogEntries() {
		return logEntries;
	}

	public void setLogEntries(List<LogEntry> logEntries) {
		this.logEntries = logEntries;
	}
}
