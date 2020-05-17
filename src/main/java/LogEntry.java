import java.io.Serializable;

public class LogEntry<T> implements Serializable {
	private int index;
	private int term;
	private T command;
	private boolean committed;
	// TODO: define the data structure of a log entry

	public int getIndex() {
		return index;
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public int getTerm() {
		return term;
	}

	public void setTerm(int term) {
		this.term = term;
	}

	public T getCommand() {
		return command;
	}

	public void setCommand(T command) {
		this.command = command;
	}

	public boolean isCommitted() {
		return committed;
	}

	public void setCommitted(boolean committed) {
		this.committed = committed;
	}
}
