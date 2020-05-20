import java.io.Serializable;

public class LogEntry<T> implements Serializable {
	public final int index;
	public final int term;
	public final T command;
	private boolean committed = false;

	public LogEntry(int index, int term, T command) {
		this.index = index;
		this.term = term;
		this.command = command;
	}

	public LogEntry(int index, int term, T command, boolean committed) {
		this.index = index;
		this.term = term;
		this.command = command;
		this.committed = committed;
	}

	public boolean isCommitted() {
		return committed;
	}

	public void commit() {
		this.committed = committed;
	}
}
