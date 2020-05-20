import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The state machine, which is a key-value database
 */
public class StateMachine {
	private Map<String, String> db = new HashMap<>();

	/**
	 * set command. e.g. [set name joseph]
	 * @param key key
	 * @param value value
	 * @return result
	 */
	public String set(String key, String value) {
		db.put(key, value);
		return "OK";
	}

	/**
	 * get command. e.g. [get name]
	 * @param key key
	 * @return stored value, "null" if no vaule is found
	 */
	public String get(String key) {
		return db.getOrDefault(key, "null");
	}

	/**
	 * get all keys in the db. e.g. [keys]
	 * @return key set
	 */
	public Set<String> keys() {
		return db.keySet();
	}
}
