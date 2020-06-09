import com.google.gson.Gson;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A utility class for reading and writing JSON file
 */
@SuppressWarnings("ResultOfMethodCallIgnored")
public class JsonFileUtil {
	/**
	 * Read states from a file after crashing
	 *
	 * @param path File path
	 * @return Persistent state
	 */
	public static PersistentState readPersistentState(String path) {
		String fileContent = readFile(path);
		try {
			return new Gson().fromJson(fileContent, PersistentState.class);
		} catch (Exception e) {
			/* error parsing the json */
			return null;
		}
	}

	/**
	 * Write persistent state to a file
	 *
	 * @param path            File path
	 * @param persistentState Persistent state to write
	 */
	public static void writePersistentState(String path, PersistentState persistentState) {
		writeFile(path, new Gson().toJson(persistentState));
	}

	/**
	 * Read config information from a file when starting
	 *
	 * @param path File path
	 * @return Config info
	 */
	public static Config readConfig(String path) {
		String fileContent = readFile(path);
		return new Gson().fromJson(fileContent, Config.class);
	}

	/**
	 * Read content from a file
	 *
	 * @param path File path
	 * @return Content of the file
	 */
	private static String readFile(String path) {
		File file = new File(path);
		try {
			FileInputStream inputStream = new FileInputStream(file);
			byte[] bytes = new byte[(int)file.length()];
			inputStream.read(bytes);
			return new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException e) {
		}
		return "";
	}

	/**
	 * Write content to a file. Will overwrite the previous content
	 *
	 * @param content Content to write
	 * @param path    File path
	 */
	private static void writeFile(String path, String content) {
		createFile(path);
		FileWriter writer;
		try {
			writer = new FileWriter(path, false);
			writer.write(content);
			writer.flush();
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Create a file of the path. Do nothing if file exists
	 *
	 * @param path File path
	 */
	private static void createFile(String path) {
		File file = new File(path);
		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
