import java.io.Serializable;

public class LogEntry implements Serializable {
	public final int index;
	public final int term;
	public final String command;
	private boolean committed = false;

	public LogEntry(int index, int term, String command) {
		this.index = index;
		this.term = term;
		this.command = command;
	}

	public LogEntry(int index, int term, String command, boolean committed) {
		this.index = index;
		this.term = term;
		this.command = command;
		this.committed = committed;
	}

	public boolean isCommitted() {
		return committed;
	}

	public void commit() {
		this.committed = true;
	}
}
