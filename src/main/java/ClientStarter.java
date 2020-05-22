import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.rmi.Naming;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientStarter {
	public static void main(String[] args) {
		String configPath = null;
		if (args.length == 1) {
			configPath = args[0];
		} else {
			ClassLoader classLoader = NodeStarter.class.getClassLoader();
			URL resource = classLoader.getResource("clusterConfig.json");
			if (resource != null) {
				configPath = resource.getPath();
			}
		}
		if (configPath == null) {
			System.err.println("No config file!");
			return;
		}
		/* read clustet info from a config file */
		Config config = JsonFileUtil.readConfig(configPath);
		List<Config.NodeInfo> clusterInfo = config.clusterInfo;

		/* A map of remote nodes: <node id, IClientInterface instance> */
		Map<Integer, IClientInterface> remoteNodes = new HashMap<>();

		/* For reading commands */
		InstructionParser parser = new InstructionParser();
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

		while (true) {
			try {
				Instruction instruction = parser.parseInstruction(reader.readLine());
				if (instruction == null) {
					System.err.println("Invalid instruction");
					continue;
				}
				if (instruction.command == Instruction.Command.QUIT) {
					break;
				}

				if (instruction.command == Instruction.Command.SEND) {
					int nodeDirect = clusterInfo.get(0).nodeId;
					if (instruction.argOptions.size() > 0) {
						// TODO: Since there is only possible argOptions type, the first element will be NODE_DIRECT
						// Cannot think of a better way for now :)
						nodeDirect = Integer.parseInt(instruction.argOptions.get(0).getValue());
					}

					/* The target remote node */
					IClientInterface remoteNode;
					if (!remoteNodes.containsKey(nodeDirect)) {
						/* Haven't got connection to the node yet */
						Config.NodeInfo info = null;
						/* Find information of the node with given id */
						for (Config.NodeInfo nodeInfo : clusterInfo) {
							if (nodeInfo.nodeId == nodeDirect) {
								info = nodeInfo;
							}
						}
						if (info == null) {
							System.err.println("No such node: " + nodeDirect);
							continue;
						}
						/* Construct a rmi url for the remote node */
						String remoteUrl = "rmi://" + info.address + ":" + info.port + "/node" + info.nodeId;
						try {
							/* Get the INode stub from remote registry */
							remoteNode = (IClientInterface)Naming.lookup(remoteUrl);
							/* Add to the map. Next time it can get the connection from the map directly */
							remoteNodes.put(info.nodeId, remoteNode);
						} catch (NotBoundException | MalformedURLException e) {
							e.printStackTrace();
							continue;
						}
					} else {
						remoteNode = remoteNodes.get(nodeDirect);
					}
					/* Invoke sendCommand */
					// TODO: implement retry
					try {
						String res = remoteNode.sendCommand(instruction.payload, Instruction.DEFAULT_TIMEOUT);
						System.out.println(res);
					} catch (RemoteException e) {
						System.out.println("Connection failed. Please try again");
						remoteNodes.remove(nodeDirect);
					}
				} else if (instruction.command == Instruction.Command.LIST) {
					// TODO: print a list of commands as instruction
				}

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

	/**
	 * Class to parse user instructions
	 */
	private static class InstructionParser {
		private static final Map<String, Instruction.Command> commandMap = new HashMap<>();
		private static final Map<String, Instruction.NoArgOption> noArgOptionMap = new HashMap<>();
		private static final Map<String, Instruction.ArgOption> argOptionMap = new HashMap<>();

		private InstructionParser() {
			registerCommands();
			registerNoArgOptions();
			registerArgOptions();
		}

		/**
		 * Parse user input into an Instruction object.
		 * @param instruction user input as String
		 * @return Instruction object (or null if could not be parsed)
		 */
		private Instruction parseInstruction(String instruction) {
			/* command: single word (potentially with hyphens between word chars) */
			Pattern commandPattern = Pattern.compile("\\s*\\w+(-\\w+)*");
			/* option: same as command but with leading "--" or "-" */
			Pattern optionPattern = Pattern.compile("\\s*-{1,2}\\w+(-\\w+)*");
			/* option argument: single word (potentially with hyphens between word chars) OR multiple words within
			 * quotes */
			Pattern argumentPattern = Pattern.compile("\\s*(\\w+(-\\w+)*|\"[-\\w\\s]*\")");
			/* command payload: multiple words (may include quotes) */
			Pattern payloadPattern = Pattern.compile("\\s*[\"\\w]+(-[\"\\w]+)*(\\s+[\"\\w]+(-[\"\\w]+)*)*");

			String remainder = instruction;

			// match the command
			Matcher matcher = commandPattern.matcher(remainder);
			if (!matcher.lookingAt() || !commandMap.containsKey(matcher.group().trim()))
				return null;
			Instruction.Command command = commandMap.get(matcher.group().trim());

			List<Instruction.NoArgOption> noArgOptions = new ArrayList<>();
			List<Map.Entry<Instruction.ArgOption, String>> argOptions = new ArrayList<>();
			String payload = null;

			// then match options and payload
			while (true) {
				// advance the matcher, discarding the already matched portion
				remainder = advanceMatcher(matcher, remainder);

				// try matching an option (e.g. --auto-retry or -a) before the command payload
				matcher.usePattern(optionPattern);
				if (matcher.lookingAt()) {
					String option = matcher.group().trim();

					if (argOptionMap.containsKey(option)) {
						// this option takes an argument
						remainder = advanceMatcher(matcher, remainder);
						matcher.usePattern(argumentPattern);
						if (!matcher.lookingAt())
							// argument is missing
							return null;
						String argument = matcher.group().trim();
						argOptions.add(new AbstractMap.SimpleImmutableEntry<>(argOptionMap.get(option), argument));

					} else if (noArgOptionMap.containsKey(option)) {
						// valid option requiring no argument
						noArgOptions.add(noArgOptionMap.get(option));

					} else {
						// the option is invalid
						return null;
					}

					// start again and try to match more options
					continue;
				}

				// try matching the command payload if matching as an option fails
				matcher.usePattern(payloadPattern);
				if (matcher.lookingAt()) {
					if (payload != null)
						// already matched the payload; invalid instruction input
						return null;
					payload = matcher.group().trim();

					// options may be included after the payload; attempt to match
					continue;
				}

				break;
			}

			// ensure that there's nothing left on the line
			matcher.usePattern(Pattern.compile("\\w*$"));
			if (!matcher.lookingAt())
				return null;
			return new Instruction(command, noArgOptions, argOptions, payload);
		}

		/**
		 * Advance a Matcher to start matching from the end of the previously matched Pattern.
		 * @param matcher the Matcher object
		 * @param input   the input string previously provided to the matcher
		 * @return the remainder of the input string (substring after portion matched)
		 */
		private String advanceMatcher(Matcher matcher, String input) {
			String remainder = input.substring(matcher.end());
			matcher.reset(remainder);
			return remainder;
		}

		private static void registerCommands() {
			commandMap.put("quit", Instruction.Command.QUIT);
			commandMap.put("q", Instruction.Command.QUIT);
			commandMap.put("exit", Instruction.Command.QUIT);
			commandMap.put("send", Instruction.Command.SEND);
			commandMap.put("s", Instruction.Command.SEND);
			commandMap.put("list", Instruction.Command.LIST);
			commandMap.put("l", Instruction.Command.LIST);
		}

		private static void registerNoArgOptions() {
			noArgOptionMap.put("--auto-retry", Instruction.NoArgOption.AUTO_RETRY);
			noArgOptionMap.put("-a", Instruction.NoArgOption.AUTO_RETRY);
		}

		private static void registerArgOptions() {
			argOptionMap.put("--node", Instruction.ArgOption.NODE_DIRECT);
			argOptionMap.put("-n", Instruction.ArgOption.NODE_DIRECT);
		}
	}

	/**
	 * Class to represent a user instruction, composed of:
	 * - A command (e.g. quit, send)
	 * - A (possibly empty) list of NoArgOptions (for options taking no argument)
	 * - A (possibly empty) list of [ArgOption, String] pairs (for options taking an argument)
	 * - A (possibly empty) payload
	 */
	private static class Instruction {
		private static final int DEFAULT_TIMEOUT = 15000;

		private enum Command {
			/* Quit the client program */
			QUIT,
			/* Issue a command to the cluster */
			SEND,
			/* Show info of known nodes */
			LIST
		}
		private enum NoArgOption {
			/* Used with Command.SEND: automatically retry to another node upon timeout */
			AUTO_RETRY,
		}
		private enum ArgOption {
			/* Used with Command.SEND: direct to a specific node (argument: node ID) */
			NODE_DIRECT,
		}

		private final Instruction.Command command;
		private final List<NoArgOption> noArgOptions;
		private final List<Map.Entry<Instruction.ArgOption, String>> argOptions;
		private final String payload;

		private Instruction(Instruction.Command command, List<NoArgOption> noArgOptions,
		                    List<Map.Entry<ArgOption, String>> argOptions, String payload) {
			this.command = command;
			this.noArgOptions = noArgOptions;
			this.argOptions = argOptions;
			this.payload = payload;
		}

		@Override
		public String toString() {
			StringBuilder s = new StringBuilder("COMMAND: " + command.toString());
			if (payload != null)
				s.append("\nPAYLOAD: ").append(payload);
			if (noArgOptions.size() > 0 || argOptions.size() > 0) {
				s.append("\nARGS:");
				for (Instruction.NoArgOption option : noArgOptions)
					s.append(" ").append(option.toString());
				for (Map.Entry<ArgOption, String> optionWithArg : argOptions)
					s.append(" ").append(optionWithArg.toString());
			}
			return s.toString();
		}
	}
}
