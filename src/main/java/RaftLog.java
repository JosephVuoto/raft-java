import java.util.List;

public class RaftLog {
	// TODO: some methods will be needed to manage the log
	private List<LogEntry> logEntries;

	/**
	 * @return the index of the most recent log entry (committed or otherwise).
	 * Note: indexing begins at 1.
	 */
	public int getLastEntryIndex() {
		return logEntries.size() + 1;
	}

	/**
	 * @return the index of the most recent committed log entry.
	 * Note: indexing begins at 1.
	 */
	public int getLastCommittedIndex() {
		// TODO: implement
		return 0;
	}

	/**
	 * Retrieve the term number associated with a particular log entry.
	 * @param entryIndex the index of the log entry
	 * @return the term number associated with the log entry
	 */
	public int getTermOfEntry(int entryIndex) {
		// TODO: implement
		return 0;
	}

	public List<LogEntry> getLogEntries() {
		return logEntries;
	}
}
