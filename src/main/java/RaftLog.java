import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.log4j.Logger;

public class RaftLog {
	static final Logger logger = Logger.getLogger(RaftLog.class.getName());

	private List<LogEntry> logEntries;
	private LogEntry lastCommitted;
	private final StateMachine stateMachine;

	public RaftLog() {
		logEntries = new CopyOnWriteArrayList<>();
		stateMachine = new StateMachine();
	}

	/**
	 * @return the index of the most recent log entry (committed or otherwise; 0 if no entries exist).
	 * Note: indexing begins at 1.
	 */
	public int getLastEntryIndex() {
		return logEntries.size();
	}

	/**
	 * @return the index of the most recent committed log entry (0 if none committed).
	 * Note: indexing begins at 1.
	 */
	public int getLastCommittedIndex() {
		return lastCommitted == null ? 0 : lastCommitted.index;
	}

	/**
	 * Retrieve the term number associated with a particular log entry.
	 * @param entryIndex the index of the log entry
	 * @return the term number associated with the log entry (0 if entry doesn't exist)
	 */
	public int getTermOfEntry(int entryIndex) {
		try {
			return logEntries.get(entryIndex).term;
		} catch (IndexOutOfBoundsException e) {
			return 0;
		}
	}

	/**
	 * Write entries to the log from a particular index.
	 * @param fromIndex Raft index at which to start writing (any existing entries here and beyond will be cleared)
	 * @param entries   log entries to be written
	 * @throws MissingEntriesException          if the log would become discontinuous
	 * @throws OverwriteCommittedEntryException if attempting to overwrite a committed entry
	 */
	public void writeEntries(int fromIndex, List<LogEntry> entries)
	    throws MissingEntriesException, OverwriteCommittedEntryException {
		logger.info("writeEntries invoked: fromIndex = " + fromIndex);
		// ensure the log is continuous
		if (fromIndex > getLastEntryIndex() + 1)
			throw new MissingEntriesException(fromIndex, getLastEntryIndex());
		// ensure that committed entries are not overwritten
		if (fromIndex <= getLastCommittedIndex())
			throw new OverwriteCommittedEntryException(fromIndex, logEntries.get(getLastCommittedIndex()).term);

		// clear any existing entries from index to be overwritten (note: Raft entries start at 1)
		logEntries.subList(fromIndex - 1, logEntries.size()).clear();

		// append new entries
		logEntries.addAll(entries);
	}

	public synchronized LogEntry addNewEntry(int term, String command) {
		int index = getLastEntryIndex() + 1;
		LogEntry entry = new LogEntry(index, term, command);
		return entry;
	}

	/**
	 * Commit all log entries until a given index.
	 * @param lastToCommit index of the last entry to be committed (inclusive)
	 * @throws MissingEntriesException if the entry with index `lastToCommit` does not exist
	 */
	public String commitToIndex(int lastToCommit) throws MissingEntriesException {
		if (lastToCommit > getLastEntryIndex())
			throw new MissingEntriesException(lastToCommit, getLastEntryIndex());
		String returnValue = null;
		for (int i = getLastCommittedIndex(); i < lastToCommit; i++) {
			lastCommitted = logEntries.get(i);
			lastCommitted.commit();
			returnValue = applyLog(lastCommitted);
		}
		return returnValue;
	}

	/**
	 * Apply the command to state machine
	 * @param entry log entry to apply
	 */
	private String applyLog(LogEntry entry) {
		String command = entry.command;
		String[] commandArgs = command.split("\\s+");
		if ("set".equals(commandArgs[0])) {
			return stateMachine.set(commandArgs[1], commandArgs[2]);
		} else if ("del".equals(commandArgs[0])) {
			return stateMachine.del(commandArgs[1]);
		}
		return "Invalid arguments";
	}

	/**
	 * Abstract class for exceptions thrown by RaftLog methods
	 */
	public static abstract class LogWriteException extends Exception {
		/* Attempted write to log at this index */
		public final int attemptedWriteAtIndex;

		public LogWriteException(int attemptedWriteAtIndex) {
			this.attemptedWriteAtIndex = attemptedWriteAtIndex;
		}
	}

	/**
	 * Exception to be thrown when attempting to overwrite a committed entry in the log
	 */
	public static class OverwriteCommittedEntryException extends LogWriteException {
		/* Term of the entry which was not overwritten due to being committed */
		public final int existingEntryTerm;

		public OverwriteCommittedEntryException(int attemptedWriteAtIndex, int existingEntryTerm) {
			super(attemptedWriteAtIndex);
			this.existingEntryTerm = existingEntryTerm;
		}
	}

	/**
	 * Exception to be thrown when attempting to write from an index greater than the highest log entry index + 1
	 */
	public static class MissingEntriesException extends LogWriteException {
		/* Index of the last log entry which already existed in the log */
		public final int lastEntryIndex;

		public MissingEntriesException(int attemptedWriteAtIndex, int lastEntryIndex) {
			super(attemptedWriteAtIndex);
			this.lastEntryIndex = lastEntryIndex;
		}
	}

	public List<LogEntry> getLogEntries() {
		return logEntries;
	}

	public void setLogEntries(List<LogEntry> logEntries) {
		this.logEntries = logEntries;
	}

	public StateMachine getStateMachine() {
		return stateMachine;
	}
}
