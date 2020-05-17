import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ClientStarter {
	public static void main(String[] args) {
		// TODO: implement
		// 1. read cluster info in a config file
		// 2. get IClientInterface instance from the remote registry
		// 3. Read from command line and invoke the remote method
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			try {
				String command = reader.readLine();
				if ("exit".equals(command)) {
					break;
				}
				// TODO: invoke sendCommand(String command)
			} catch (IOException e) {
				try {
					reader.close();
				} catch (IOException ioException) {
					ioException.printStackTrace();
				}
				e.printStackTrace();
				break;
			}
		}
	}
}
