import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClientStarter {
	public static void main(String[] args) {
		// TODO: implement
		// 1. read cluster info in a config file
		// 2. get IClientInterface instance from the remote registry
		// 3. Read from command line and invoke the remote method
		InstructionParser parser = new InstructionParser();
		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			try {
				Instruction instruction = parser.parseInstruction(reader.readLine());
				if (instruction.command == Instruction.Command.QUIT) {
					break;
				}
				// TODO: invoke sendCommand(String command)
				if (instruction != null)
					System.out.println(instruction.toString());
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
	 *  Class to parse user instructions
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
			Pattern commandPattern = Pattern.compile("^\\s*\\w+\\s*");
			Pattern optionPattern = Pattern.compile("(--\\w+(-\\w+)*)|(-\\w)\\s*");
			Pattern argumentPattern = Pattern.compile("\\w+\\s*");

			String remainder = instruction;

			// first match the command
			Matcher matcher = commandPattern.matcher(remainder);
			if (!matcher.find())
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
				matcher.usePattern(argumentPattern);
				if (matcher.lookingAt()) {
					payload = matcher.group().trim();

					// options may be included after the payload; attempt to match
					continue;
				}

				break;
			}
			return new Instruction(command, noArgOptions, argOptions, payload);
		}

		/**
		 * Advance a Matcher to start matching from the end of the previously matched Pattern.
		 * @param matcher the Matcher object
		 * @param input the input string previously provided to the matcher
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
			String s = "COMMAND: " + command.toString();
			if (payload != null)
				s += "\nPAYLOAD: " + payload;
			if (noArgOptions.size() > 0 || argOptions.size() > 0) {
				s += "\nARGS:";
				for (Instruction.NoArgOption option : noArgOptions)
					s += " " + option.toString();
				for (Map.Entry<ArgOption, String> optionWithArg : argOptions)
					s += " " + optionWithArg.toString();
			}
			return s;
		}
	}
}
