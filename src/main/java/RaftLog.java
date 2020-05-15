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
}
