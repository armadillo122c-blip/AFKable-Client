package com.example;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text scripting runtime.
 *
 * <p><b>Backward compatibility contract.</b> Every command that parsed before still
 * parses, keeps its opcode, and still costs exactly one client tick to execute, so the
 * timing of old scripts is unchanged. New commands are only ever appended to the end of
 * the parse chain (or guarded by a start-anchored pattern), which means a line that used
 * to be an error can become a command, but a line that used to be a command can never
 * change meaning.</p>
 *
 * <p><b>Execution model.</b> One "costly" instruction per tick, exactly as before.
 * Purely computational instructions added by this version (set/add/while/break/log/...)
 * are free and several of them run inside the same tick, bounded by
 * {@link #MAX_FREE_STEPS_PER_TICK} steps and {@link #TICK_BUDGET_NANOS} of wall clock,
 * so a runaway script can never freeze the game.</p>
 *
 * <p><b>Events.</b> A script that declares handlers ("sectioned mode") stays armed after
 * its main body finishes and reacts to chat / GUI / slot / pickup / interval / join
 * events. Scripts that only contain the legacy {@code on join} marker are parsed in
 * "legacy mode" and behave byte-for-byte as they did before.</p>
 */
public class ScriptEngine {

	// ------------------------------------------------------------------
	// safety caps - see the class javadoc
	// ------------------------------------------------------------------

	/** Free (non tick-consuming) instructions allowed inside a single client tick. */
	public static final int MAX_FREE_STEPS_PER_TICK = 512;
	/** Wall-clock budget for one tick of script execution (20 ms of a 50 ms tick). */
	public static final long TICK_BUDGET_NANOS = 20_000_000L;
	/** Iterations a single {@code while} frame may run before it is aborted. */
	public static final int MAX_WHILE_ITERATIONS = 1_000_000;
	/** Wall clock a single {@code while} frame may live for before it is aborted. */
	public static final long MAX_WHILE_MILLIS = 600_000L;
	/**
	 * "Spin pressure": iterations that did not advance the game tick, minus one per tick
	 * that does pass. A {@code while} whose body never costs a tick trips this in about
	 * ten seconds; a loop that waits on anything real never leaves zero.
	 */
	public static final int MAX_WHILE_SPIN = 50_000;
	/** Nesting depth for loops/whiles. */
	public static final int MAX_FRAMES = 16;
	/** Highest count accepted by {@code repeat N times} / {@code loop N times}. */
	public static final int MAX_REPEAT = 1_000_000_000;
	/** Events waiting for the runtime to go idle. Older ones are dropped. */
	public static final int MAX_EVENT_QUEUE = 8;
	/** Hard ceiling on parsed instructions, so a pathological file cannot eat the heap. */
	public static final int MAX_INSTRUCTIONS = 50_000;

	/** {@code loop forever} count - unchanged from the original engine. */
	private static final int FOREVER = Integer.MAX_VALUE / 2;

	// ------------------------------------------------------------------
	// model
	// ------------------------------------------------------------------

	private record Instruction(String type, String arg, int ticks, int line) {
	}

	/** Instruction types that do NOT consume a tick. Every legacy opcode is absent here. */
	private static final Set<String> FREE_OPS = Set.of(
			"set", "addvar", "while", "endwhile", "break", "log",
			"endhandler", "halt", "else", "nop");

	/** One live loop/while frame. */
	private static final class Frame {
		final String kind;      // "loop" or "while"
		final int headerIndex;  // index of the loop/while instruction itself
		final int bodyStart;    // first instruction of the body
		long remaining;         // loop only
		int iterations;
		int spins;              // while only - see MAX_WHILE_SPIN
		long lastTick = -1;
		final long startMs = System.currentTimeMillis();

		Frame(String kind, int headerIndex, int bodyStart, long remaining) {
			this.kind = kind;
			this.headerIndex = headerIndex;
			this.bodyStart = bodyStart;
			this.remaining = remaining;
		}
	}

	/** A declared event handler. {@code start} points into {@link #program}. */
	private static final class Handler {
		final String kind;   // join | chat | guiopen | guiclose | slotchange | pickup | interval
		final String arg;    // title / item / raw regex - may be null
		final Pattern regex; // chat only
		final int slot;      // slotchange only, -1 = any
		final int interval;  // interval only
		int start;
		int counter;

		Handler(String kind, String arg, Pattern regex, int slot, int interval) {
			this.kind = kind;
			this.arg = arg;
			this.regex = regex;
			this.slot = slot;
			this.interval = interval;
		}
	}

	private record Pending(Handler handler, Map<String, String> context) {
	}

	public record GuiDef(String title, List<String[]> buttons) {
	}

	// ------------------------------------------------------------------
	// legacy patterns - ORDER AND SHAPE PRESERVED
	// ------------------------------------------------------------------

	private static final Pattern WAIT_SEC = Pattern.compile("wait\\s+(\\d+)\\s+sec");
	private static final Pattern WAIT_TICK = Pattern.compile("wait\\s+(\\d+)\\s+tick");
	private static final Pattern RUN_CMD = Pattern.compile("run\\s+command\\s+\"(.+?)\"");
	private static final Pattern SEND_CHAT = Pattern.compile("send\\s+\"(.+?)\"");
	private static final Pattern SAY = Pattern.compile("say\\s+\"(.+?)\"");
	private static final Pattern LOOP = Pattern.compile("loop\\s+(\\d+)\\s+times");
	// anchored: an un-anchored "gui \"...\"" also matched "if gui \"...\"" and
	// "wait for gui \"...\"", which silently turned those two commands into GUI
	// definitions. See the report notes.
	private static final Pattern GUI = Pattern.compile("^gui\\s+\"(.+?)\"");
	private static final Pattern CLICK_SLOT = Pattern.compile("click\\s+slot\\s+(\\d+)");
	private static final Pattern SHIFT_CLICK = Pattern.compile("shift\\s+click\\s+slot\\s+(\\d+)");
	private static final Pattern WAIT_GUI = Pattern.compile("wait\\s+for\\s+gui\\s+\"(.+?)\"");
	private static final Pattern PRESS = Pattern.compile("(?:press\\s+)?(attack|use|jump|forward)\\s+for\\s+(\\d+)\\s+ticks?");
	private static final Pattern SNEAK = Pattern.compile("sneak\\s+(on|off)");
	private static final Pattern CHAT_MSG = Pattern.compile("chat\\s+\"(.+?)\"");
	private static final Pattern ADD_GUI_HUD = Pattern.compile("^add\\s+gui\\s+to\\s+hud(?:\\s+\"(.+?)\")?");
	private static final Pattern REMOVE_GUI_HUD = Pattern.compile("^remove\\s+gui\\s+from\\s+hud(?:\\s+\"(.+?)\")?");
	private static final Pattern WAIT_RANDOM = Pattern.compile("wait\\s+random\\s+(\\d+)\\s+to\\s+(\\d+)\\s+sec");
	private static final Pattern IF_GUI = Pattern.compile("^if\\s+gui\\s+\"(.+?)\"");
	private static final Pattern HOTBAR = Pattern.compile("^hotbar\\s+([1-9])");
	private static final Pattern LOOK = Pattern.compile("^look\\s+(-?\\d+)\\s+(-?\\d+)");
	private static final Pattern NOTIFY = Pattern.compile("^notify\\s+\"(.+?)\"");
	private static final Pattern ADD_BUTTON = Pattern.compile("^add\\s+button\\s+\"(.+?)\"\\s*->\\s*\"(.+?)\"");
	private static final Pattern BUTTON = Pattern.compile("^button\\s+\"(.+?)\"\\s*->\\s*\"(.+?)\"");

	// ------------------------------------------------------------------
	// new patterns
	// ------------------------------------------------------------------

	private static final int CI = Pattern.CASE_INSENSITIVE;

	private static final Pattern ON_HEADER = Pattern.compile("^on\\s+(.+)$", CI);
	private static final Pattern EV_JOIN = Pattern.compile("^join\\b.*$", CI);
	private static final Pattern EV_CHAT = Pattern.compile("^chat\\s+(?:match|matching|matches|regex|contains|containing)\\s+\"(.*)\"$", CI);
	private static final Pattern EV_GUI_OPEN = Pattern.compile("^gui\\s+open(?:\\s+\"(.*)\")?$", CI);
	private static final Pattern EV_GUI_CLOSE = Pattern.compile("^gui\\s+close[ds]?$", CI);
	private static final Pattern EV_SLOT_ANY = Pattern.compile("^slot\\s+change[ds]?(?:\\s+(\\d+))?$", CI);
	private static final Pattern EV_SLOT_N = Pattern.compile("^slot\\s+(\\d+)\\s+change[ds]?$", CI);
	private static final Pattern EV_PICKUP = Pattern.compile("^(?:item\\s+)?(?:pickup|picked\\s+up|pick\\s+up)(?:\\s+\"(.*)\")?$", CI);
	private static final Pattern EV_TICK = Pattern.compile("^(?:tick|every)\\s+(\\d+)(?:\\s+ticks?)?$", CI);
	private static final Pattern EV_INTERVAL = Pattern.compile("^(?:tick\\s+)?interval\\s+(\\d+)$", CI);

	private static final Pattern RIGHT_CLICK = Pattern.compile("^right[\\s-]*click\\s+slot\\s+(\\d+)$", CI);
	private static final Pattern LEFT_CLICK = Pattern.compile("^left[\\s-]*click\\s+slot\\s+(\\d+)$", CI);
	private static final Pattern SHIFT_CLICK2 = Pattern.compile("^shift[\\s-]*(?:left\\s+)?click\\s+slot\\s+(\\d+)$", CI);

	private static final Pattern SET_VAR = Pattern.compile("^set\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|\\bto\\b)\\s*(.+)$", CI);
	private static final Pattern ADD_VAR = Pattern.compile("^add\\s+(.+?)\\s+to\\s+([A-Za-z_][A-Za-z0-9_]*)$", CI);
	private static final Pattern SUB_VAR = Pattern.compile("^(?:sub|subtract|take)\\s+(.+?)\\s+from\\s+([A-Za-z_][A-Za-z0-9_]*)$", CI);
	private static final Pattern INC_VAR = Pattern.compile("^(?:increment|increase|inc)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+by\\s+(.+))?$", CI);
	private static final Pattern DEC_VAR = Pattern.compile("^(?:decrement|decrease|dec)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s+by\\s+(.+))?$", CI);
	private static final Pattern LOG_KIND = Pattern.compile("^log\\s+([A-Za-z]+)\\s+\"(.*)\"$", CI);
	private static final Pattern LOG_PLAIN = Pattern.compile("^log\\s+\"(.*)\"$", CI);
	private static final Pattern IF_NOT = Pattern.compile("^if\\s+not\\s+(.+)$", CI);
	private static final Pattern IF_ANY = Pattern.compile("^if\\s+(.+)$", CI);
	private static final Pattern WHILE_ANY = Pattern.compile("^while\\s+(.+)$", CI);
	private static final Pattern REPEAT_N = Pattern.compile("^repeat\\s+(\\d+)\\s+times?$", CI);
	private static final Pattern SEND_COMMAND = Pattern.compile("^(?:send|open|do|execute|exec)\\s+command\\s+\"(.+?)\"$", CI);
	private static final Pattern OPEN_GUI_CMD = Pattern.compile("^open\\s+gui\\s+\"(.+?)\"$", CI);

	// condition predicates
	private static final Pattern C_GUI = Pattern.compile("^gui\\s+\"(.*)\"$", CI);
	private static final Pattern C_GUI_TITLE = Pattern.compile("^gui\\s+title\\s+(?:contains|has)\\s+\"(.*)\"$", CI);
	private static final Pattern C_GUI_OPEN = Pattern.compile("^(?:gui\\s+open|any\\s+gui|gui\\s+is\\s+open)$", CI);
	private static final Pattern C_NO_GUI = Pattern.compile("^(?:no\\s+gui|gui\\s+closed|gui\\s+is\\s+closed)$", CI);
	private static final Pattern C_SLOT_HAS = Pattern.compile("^slot\\s+(\\d+)\\s+(?:has|contains|is)\\s+\"(.*)\"$", CI);
	private static final Pattern C_SLOT_EMPTY = Pattern.compile("^slot\\s+(\\d+)\\s+(?:is\\s+)?empty$", CI);
	private static final Pattern C_SLOT_FILLED = Pattern.compile("^slot\\s+(\\d+)\\s+(?:is\\s+)?(?:not\\s+empty|filled|full)$", CI);
	private static final Pattern C_FREE = Pattern.compile("^(?:free\\s+space|(?:inventory|inv)\\s+(?:has\\s+)?(?:a\\s+)?free\\s+(?:slot|space)|inv\\s+free)$", CI);
	private static final Pattern C_INV_FULL = Pattern.compile("^(?:inventory|inv)\\s+(?:is\\s+)?full$", CI);
	private static final Pattern C_HEALTH = Pattern.compile("^health\\s+(above|over|below|under)\\s+(\\d+(?:\\.\\d+)?)$", CI);
	private static final Pattern C_HUNGER = Pattern.compile("^(?:hunger|food)\\s+(above|over|below|under)\\s+(\\d+(?:\\.\\d+)?)$", CI);
	private static final Pattern C_HAS_ITEM = Pattern.compile("^(?:has|have|holding|inventory\\s+has)\\s+\"(.*)\"$", CI);
	private static final Pattern C_COMPARE = Pattern.compile("^(.+?)\\s*(==|!=|<=|>=|<|>|=)\\s*(.+)$");

	private static final Pattern COLOR_CODES = Pattern.compile("(?i)§[0-9A-FK-OR]");

	// ------------------------------------------------------------------
	// state
	// ------------------------------------------------------------------

	private List<Instruction> program = new ArrayList<>();
	private final List<GuiDef> guis = new ArrayList<>();
	private final List<Handler> handlers = new ArrayList<>();
	private final Deque<Frame> frames = new ArrayDeque<>();
	private final Deque<Pending> eventQueue = new ArrayDeque<>();
	private final Map<String, Integer> vars = new LinkedHashMap<>();
	private final Map<String, String> eventVars = new LinkedHashMap<>();
	private int pointer = 0;
	private int waitTicks = 0;
	private String waitGuiTarget = null;
	private int waitGuiTimeout = 0;
	private final java.util.Map<net.minecraft.client.option.KeyBinding, Integer> heldKeys = new java.util.HashMap<>();
	private String scriptName = "";
	private boolean armed = false;
	private boolean armedAnnounced = false;
	private boolean inWorld = false;
	private long engineTicks = 0;

	// event polling snapshots
	private boolean watchGui = false;
	private boolean watchSlots = false;
	private boolean watchPickup = false;
	private String lastGuiTitle = null;
	private int slotSyncId = Integer.MIN_VALUE;
	private String[] slotSnapshot = null;
	private Map<String, Integer> pickupSnapshot = null;

	private final java.util.Random random = new java.util.Random();

	// ------------------------------------------------------------------
	// files (unchanged)
	// ------------------------------------------------------------------

	public static Path scriptFolder() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-scripts");
	}

	public static void ensureExampleScript() {
		try {
			Files.createDirectories(scriptFolder());
			Path guiExample = scriptFolder().resolve("my_gui.txt");
			if (!Files.exists(guiExample)) {
				Files.writeString(guiExample, """
						# custom GUI example
						gui "Quick Menu"
						button "Fly" -> "/fly"
						button "Stock" -> "/stock"
						button "Claude" -> "/claude"
						button "Ore Bag" -> "/orebag"
						show gui
						""");
			}
			Path example = scriptFolder().resolve("example.txt");
			if (!Files.exists(example)) {
				Files.writeString(example, """
						# AFKABLE CLIENT script
						# commands: wait N seconds / wait N ticks
						#           run command "/fly"
						#           send "hello" (public chat)
						#           say "hi" (only you see it)
						#           loop N times ... end loop
						say "Script started!"
						wait 2 seconds
						run command "/stock"
						""");
			}
			Path events = scriptFolder().resolve("events_example.txt");
			if (!Files.exists(events)) {
				Files.writeString(events, """
						# AFKABLE CLIENT - scripting 2.0 demo
						# everything below the first "on ..." line belongs to that event
						set clicks = 0

						on join
						say "welcome back"
						end on

						on chat match "(?i)stock is now (\\w+)"
						say "stock: %1%"
						log STOCK "stock changed to %1%"
						end on

						on gui open "Menu"
						if slot 11 has "Diamond"
						right click slot 11
						add 1 to clicks
						end if
						if clicks >= 5
						close gui
						stop
						end if
						end on

						on every 200 ticks
						if hunger below 7
						notify "eat something"
						end if
						end on
						""");
			}
		} catch (Exception ignored) {
		}
	}

	public static void runByName(String name, MinecraftClient client) {
		if (ForgeOreCalculatorClient.INSTANCE == null) return;
		String target = name.toLowerCase(Locale.ROOT).replace(".txt", "");
		for (Path path : listScripts()) {
			String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT).replace(".txt", "");
			if (fileName.equals(target)) {
				try {
					ForgeOreCalculatorClient.INSTANCE.scriptEngine.start(
							path.getFileName().toString(), Files.readAllLines(path), client);
				} catch (Exception ignored) {
				}
				return;
			}
		}
		if (client.player != null && ForgeOreCalculatorClient.INSTANCE != null) {
			ForgeOreCalculatorClient.INSTANCE.notifyChat(client, "§cScript §f" + name + " §cnot found");
		}
	}

	public static List<Path> listScripts() {
		List<Path> result = new ArrayList<>();
		try {
			Files.createDirectories(scriptFolder());
			try (var stream = Files.list(scriptFolder())) {
				stream.filter(p -> p.toString().endsWith(".txt")).sorted().forEach(result::add);
			}
		} catch (Exception ignored) {
		}
		return result;
	}

	// ------------------------------------------------------------------
	// lifecycle
	// ------------------------------------------------------------------

	/** True while a program is executing OR while event handlers are armed. */
	public boolean isRunning() {
		return pointer < program.size() || armed;
	}

	public String runningName() {
		return scriptName;
	}

	/** Full stop: program, handlers, variables, queued events and held keys. */
	public void stop() {
		program.clear();
		pointer = 0;
		handlers.clear();
		frames.clear();
		eventQueue.clear();
		vars.clear();
		eventVars.clear();
		armed = false;
		armedAnnounced = false;
		watchGui = false;
		watchSlots = false;
		watchPickup = false;
		slotSnapshot = null;
		pickupSnapshot = null;
		waitTicks = 0;
		waitGuiTarget = null;
		releaseAllKeys();
	}

	/** Stops the running body but keeps handlers armed (used when the world unloads). */
	private void haltProgram() {
		pointer = program.size();
		frames.clear();
		eventQueue.clear();
		waitTicks = 0;
		waitGuiTarget = null;
		slotSnapshot = null;
		pickupSnapshot = null;
		lastGuiTitle = null;
		releaseAllKeys();
	}

	private void releaseAllKeys() {
		for (net.minecraft.client.option.KeyBinding key : heldKeys.keySet()) {
			try {
				key.setPressed(false);
			} catch (Throwable ignored) {
			}
		}
		heldKeys.clear();
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.options != null) {
			try {
				mc.options.sneakKey.setPressed(false);
			} catch (Throwable ignored) {
			}
		}
	}

	// ------------------------------------------------------------------
	// parsing
	// ------------------------------------------------------------------

	public boolean start(String name, List<String> lines, MinecraftClient client) {
		if (isRunning()) {
			chat(client, "§cScript §f" + scriptName + " §cis already running - press STOP first");
			return false;
		}
		try {
			return parseAndStart(name, lines, client);
		} catch (Throwable t) {
			// a parse error must never escape into the caller
			chat(client, "§cScript §f" + name + " §cfailed to load: §7" + describe(t));
			program = new ArrayList<>();
			handlers.clear();
			pointer = 0;
			armed = false;
			return false;
		}
	}

	private boolean parseAndStart(String name, List<String> lines, MinecraftClient client) {
		List<Instruction> parsed = new ArrayList<>();
		List<GuiDef> parsedGuis = new ArrayList<>();
		List<Handler> parsedHandlers = new ArrayList<>();
		GuiDef building = null;
		boolean sectioned = detectSectioned(lines);
		boolean handlerOpen = false;
		int lineNo = 0;

		for (String rawLine : lines) {
			lineNo++;
			if (parsed.size() > MAX_INSTRUCTIONS) {
				chat(client, "§cScript is too large (over " + MAX_INSTRUCTIONS + " steps)");
				return false;
			}
			String line = rawLine == null ? "" : rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			String lower = line.toLowerCase(Locale.ROOT);

			// ---- legacy: a lone "on join" marker is stripped, exactly as before ----
			if (!sectioned && lower.startsWith("on join")) continue;

			// ---- event headers (start-anchored, so nothing legacy can reach here) ----
			Matcher header = ON_HEADER.matcher(line);
			if (header.matches()) {
				Handler handler = parseHandler(header.group(1).trim());
				if (handler == null) {
					parseError(client, lineNo, "unknown event", line);
					return false;
				}
				if (handlerOpen) {
					parsed.add(new Instruction("endhandler", null, 0, lineNo));
				} else if (parsedHandlers.isEmpty()) {
					parsed.add(new Instruction("halt", null, 0, lineNo));
				}
				handler.start = parsed.size();
				parsedHandlers.add(handler);
				handlerOpen = true;
				continue;
			}
			if (lower.equals("end on") || lower.equals("end event")) {
				if (handlerOpen) {
					parsed.add(new Instruction("endhandler", null, 0, lineNo));
					handlerOpen = false;
				}
				continue;
			}

			Matcher m;
			if ((m = ADD_BUTTON.matcher(line)).find()) {
				parsed.add(new Instruction("addbutton", m.group(1) + "\u0000" + m.group(2), 0, lineNo));
				continue;
			}
			if ((m = ADD_GUI_HUD.matcher(lower)).find()) {
				Matcher orig = ADD_GUI_HUD.matcher(line);
				orig.find();
				parsed.add(new Instruction("addguihud", orig.group(1), 0, lineNo));
				continue;
			}
			if ((m = REMOVE_GUI_HUD.matcher(lower)).find()) {
				Matcher orig = REMOVE_GUI_HUD.matcher(line);
				orig.find();
				parsed.add(new Instruction("removeguihud", orig.group(1), 0, lineNo));
				continue;
			}
			if ((m = GUI.matcher(line)).find()) {
				building = new GuiDef(m.group(1), new ArrayList<>());
				parsedGuis.add(building);
				continue;
			}
			if ((m = BUTTON.matcher(line)).find()) {
				if (building == null) {
					parseError(client, lineNo, "button before any gui \"...\" line", line);
					return false;
				}
				building.buttons().add(new String[]{m.group(1), m.group(2)});
				continue;
			}
			if (lower.equals("show gui")) {
				if (parsedGuis.isEmpty()) {
					parseError(client, lineNo, "show gui before any gui was defined", line);
					return false;
				}
				parsed.add(new Instruction("showgui", String.valueOf(parsedGuis.size() - 1), 0, lineNo));
				continue;
			}
			// "right click slot N" would otherwise be swallowed by CLICK_SLOT.find()
			if ((m = RIGHT_CLICK.matcher(line)).matches()) {
				parsed.add(new Instruction("rightclick", m.group(1), 0, lineNo));
				continue;
			}
			if ((m = LEFT_CLICK.matcher(line)).matches()) {
				parsed.add(new Instruction("clickslot", m.group(1), 0, lineNo));
				continue;
			}
			if ((m = SHIFT_CLICK2.matcher(line)).matches()) {
				parsed.add(new Instruction("shiftclick", m.group(1), 0, lineNo));
				continue;
			}

			// ---- the original chain, in the original order ----
			if ((m = WAIT_SEC.matcher(lower)).find()) {
				parsed.add(new Instruction("wait", null, safeInt(m.group(1), 0, 0, 72000) * 20, lineNo));
			} else if ((m = WAIT_TICK.matcher(lower)).find()) {
				parsed.add(new Instruction("wait", null, safeInt(m.group(1), 0, 0, 1440000), lineNo));
			} else if ((m = RUN_CMD.matcher(line)).find()) {
				parsed.add(new Instruction("cmd", m.group(1).replaceFirst("^/", ""), 0, lineNo));
			} else if ((m = SEND_CHAT.matcher(line)).find()) {
				parsed.add(new Instruction("chat", m.group(1), 0, lineNo));
			} else if ((m = SAY.matcher(line)).find()) {
				parsed.add(new Instruction("say", m.group(1), 0, lineNo));
			} else if ((m = LOOP.matcher(lower)).find()) {
				parsed.add(new Instruction("loop", String.valueOf(safeInt(m.group(1), 1, 0, MAX_REPEAT)), 0, lineNo));
			} else if (lower.equals("end loop")) {
				parsed.add(new Instruction("endloop", null, 0, lineNo));
			} else if ((m = SHIFT_CLICK.matcher(lower)).find()) {
				parsed.add(new Instruction("shiftclick", m.group(1), 0, lineNo));
			} else if ((m = CLICK_SLOT.matcher(lower)).find()) {
				parsed.add(new Instruction("clickslot", m.group(1), 0, lineNo));
			} else if ((m = WAIT_GUI.matcher(line)).find()) {
				parsed.add(new Instruction("waitgui", m.group(1).toLowerCase(Locale.ROOT), 0, lineNo));
			} else if ((m = WAIT_RANDOM.matcher(lower)).find()) {
				int min = safeInt(m.group(1), 0, 0, 72000) * 20;
				int max = safeInt(m.group(2), 0, 0, 72000) * 20;
				parsed.add(new Instruction("waitrandom", min + "," + Math.max(min, max), 0, lineNo));
			} else if ((m = IF_GUI.matcher(line)).find()) {
				parsed.add(new Instruction("ifgui", m.group(1).toLowerCase(Locale.ROOT), 0, lineNo));
			} else if (lower.equals("end if")) {
				parsed.add(new Instruction("endif", null, 0, lineNo));
			} else if ((m = HOTBAR.matcher(lower)).find()) {
				parsed.add(new Instruction("hotbar", m.group(1), 0, lineNo));
			} else if (lower.equals("drop")) {
				parsed.add(new Instruction("drop", null, 0, lineNo));
			} else if (lower.equals("swap hands")) {
				parsed.add(new Instruction("swaphands", null, 0, lineNo));
			} else if (lower.equals("open inventory")) {
				parsed.add(new Instruction("openinv", null, 0, lineNo));
			} else if ((m = LOOK.matcher(lower)).find()) {
				parsed.add(new Instruction("look", m.group(1) + "," + m.group(2), 0, lineNo));
			} else if ((m = NOTIFY.matcher(line)).find()) {
				parsed.add(new Instruction("notify", m.group(1), 0, lineNo));
			} else if (lower.equals("stop")) {
				parsed.add(new Instruction("stopscript", null, 0, lineNo));
			} else if (lower.equals("loop forever")) {
				parsed.add(new Instruction("loop", String.valueOf(FOREVER), 0, lineNo));
			} else if (lower.equals("clear buttons")) {
				parsed.add(new Instruction("clearbuttons", null, 0, lineNo));
			} else if (lower.equals("close gui")) {
				parsed.add(new Instruction("closegui", null, 0, lineNo));
			} else if (lower.equals("sort")) {
				parsed.add(new Instruction("sort", null, 0, lineNo));
			} else if ((m = PRESS.matcher(lower)).find()) {
				parsed.add(new Instruction("press:" + m.group(1), null, safeInt(m.group(2), 1, 0, 72000), lineNo));
			} else if ((m = SNEAK.matcher(lower)).find()) {
				parsed.add(new Instruction("sneak", m.group(1), 0, lineNo));
			} else if ((m = CHAT_MSG.matcher(line)).find()) {
				parsed.add(new Instruction("chat", m.group(1), 0, lineNo));
			} else if (!parseExtended(line, lower, lineNo, parsed)) {
				parseError(client, lineNo, "unknown command", line);
				return false;
			}
		}

		if (handlerOpen) {
			parsed.add(new Instruction("endhandler", null, 0, lineNo + 1));
		}
		if (parsed.isEmpty()) {
			chat(client, "§cScript is empty");
			return false;
		}

		program = parsed;
		guis.clear();
		guis.addAll(parsedGuis);
		handlers.clear();
		handlers.addAll(parsedHandlers);
		frames.clear();
		eventQueue.clear();
		vars.clear();
		eventVars.clear();
		pointer = 0;
		waitTicks = 0;
		waitGuiTarget = null;
		scriptName = name;
		armed = !handlers.isEmpty();
		armedAnnounced = false;
		// already in a world: do not let the first tick fire "on join" a second time
		inWorld = client != null && client.player != null && client.world != null;
		lastGuiTitle = null;
		slotSnapshot = null;
		pickupSnapshot = null;
		watchGui = false;
		watchSlots = false;
		watchPickup = false;
		for (Handler handler : handlers) {
			handler.counter = 0;
			if ("guiopen".equals(handler.kind) || "guiclose".equals(handler.kind)) watchGui = true;
			if ("slotchange".equals(handler.kind)) watchSlots = true;
			if ("pickup".equals(handler.kind)) watchPickup = true;
		}
		chat(client, "§7Running §f" + name + " §8(" + parsed.size() + " steps"
				+ (armed ? ", " + handlers.size() + " events" : "") + ")");

		// starting a script while already in the world counts as "joined" for the
		// join handler, which also keeps legacy "on join" scripts running instantly
		if (armed) {
			for (Handler handler : handlers) {
				if ("join".equals(handler.kind)) {
					dispatch(handler, Map.of("event", "join"));
					break;
				}
			}
		}
		return true;
	}

	/**
	 * Sectioned mode is only entered by files that use syntax the old parser rejected,
	 * so every script that loads today still parses as one flat program.
	 */
	private boolean detectSectioned(List<String> lines) {
		for (String rawLine : lines) {
			String line = rawLine == null ? "" : rawLine.trim().toLowerCase(Locale.ROOT);
			if (line.isEmpty() || line.startsWith("#")) continue;
			if (line.equals("end on") || line.equals("end event")) return true;
			if (line.startsWith("on ") && !line.startsWith("on join")) return true;
		}
		return false;
	}

	private Handler parseHandler(String rest) {
		Matcher m;
		if (EV_JOIN.matcher(rest).matches()) return new Handler("join", null, null, -1, 0);
		if ((m = EV_CHAT.matcher(rest)).matches()) {
			Pattern compiled;
			try {
				compiled = Pattern.compile(m.group(1), CI);
			} catch (Exception e) {
				compiled = Pattern.compile(Pattern.quote(m.group(1)), CI);
			}
			return new Handler("chat", m.group(1), compiled, -1, 0);
		}
		if ((m = EV_GUI_OPEN.matcher(rest)).matches()) return new Handler("guiopen", m.group(1), null, -1, 0);
		if (EV_GUI_CLOSE.matcher(rest).matches()) return new Handler("guiclose", null, null, -1, 0);
		if ((m = EV_SLOT_N.matcher(rest)).matches()) {
			return new Handler("slotchange", null, null, safeInt(m.group(1), -1, 0, 4096), 0);
		}
		if ((m = EV_SLOT_ANY.matcher(rest)).matches()) {
			return new Handler("slotchange", null, null,
					m.group(1) == null ? -1 : safeInt(m.group(1), -1, 0, 4096), 0);
		}
		if ((m = EV_PICKUP.matcher(rest)).matches()) return new Handler("pickup", m.group(1), null, -1, 0);
		if ((m = EV_TICK.matcher(rest)).matches()) {
			return new Handler("interval", null, null, -1, Math.max(1, safeInt(m.group(1), 20, 1, 1728000)));
		}
		if ((m = EV_INTERVAL.matcher(rest)).matches()) {
			return new Handler("interval", null, null, -1, Math.max(1, safeInt(m.group(1), 20, 1, 1728000)));
		}
		return null;
	}

	/** Every command added by this version. Reached only when nothing legacy matched. */
	private boolean parseExtended(String line, String lower, int lineNo, List<Instruction> parsed) {
		Matcher m;
		if ((m = SEND_COMMAND.matcher(line)).matches()) {
			parsed.add(new Instruction("cmd", m.group(1).replaceFirst("^/", ""), 0, lineNo));
			return true;
		}
		if ((m = OPEN_GUI_CMD.matcher(line)).matches()) {
			parsed.add(new Instruction("cmd", m.group(1).replaceFirst("^/", ""), 0, lineNo));
			return true;
		}
		if ((m = SET_VAR.matcher(line)).matches()) {
			parsed.add(new Instruction("set", m.group(1) + "\u0000" + m.group(2).trim(), 0, lineNo));
			return true;
		}
		if ((m = ADD_VAR.matcher(line)).matches()) {
			parsed.add(new Instruction("addvar", m.group(2) + "\u0000" + m.group(1).trim(), 0, lineNo));
			return true;
		}
		if ((m = SUB_VAR.matcher(line)).matches()) {
			parsed.add(new Instruction("addvar", m.group(2) + "\u0000-(" + m.group(1).trim() + ")", 0, lineNo));
			return true;
		}
		if ((m = INC_VAR.matcher(line)).matches()) {
			String by = m.group(2) == null ? "1" : m.group(2).trim();
			parsed.add(new Instruction("addvar", m.group(1) + "\u0000" + by, 0, lineNo));
			return true;
		}
		if ((m = DEC_VAR.matcher(line)).matches()) {
			String by = m.group(2) == null ? "1" : m.group(2).trim();
			parsed.add(new Instruction("addvar", m.group(1) + "\u0000-(" + by + ")", 0, lineNo));
			return true;
		}
		if ((m = LOG_PLAIN.matcher(line)).matches()) {
			parsed.add(new Instruction("log", "SYSTEM\u0000" + m.group(1), 0, lineNo));
			return true;
		}
		if ((m = LOG_KIND.matcher(line)).matches()) {
			parsed.add(new Instruction("log", m.group(1).toUpperCase(Locale.ROOT) + "\u0000" + m.group(2), 0, lineNo));
			return true;
		}
		if ((m = REPEAT_N.matcher(line)).matches()) {
			parsed.add(new Instruction("loop", String.valueOf(safeInt(m.group(1), 1, 0, MAX_REPEAT)), 0, lineNo));
			return true;
		}
		if (lower.equals("repeat forever")) {
			parsed.add(new Instruction("loop", String.valueOf(FOREVER), 0, lineNo));
			return true;
		}
		if (lower.equals("end repeat")) {
			parsed.add(new Instruction("endloop", null, 0, lineNo));
			return true;
		}
		if (lower.equals("end while")) {
			parsed.add(new Instruction("endwhile", null, 0, lineNo));
			return true;
		}
		if (lower.equals("break")) {
			parsed.add(new Instruction("break", null, 0, lineNo));
			return true;
		}
		if (lower.equals("else")) {
			parsed.add(new Instruction("else", null, 0, lineNo));
			return true;
		}
		if (lower.equals("end")) {
			// tolerated shorthand for a dangling block terminator
			parsed.add(new Instruction("endif", null, 0, lineNo));
			return true;
		}
		if ((m = WHILE_ANY.matcher(line)).matches()) {
			parsed.add(new Instruction("while", m.group(1).trim(), 0, lineNo));
			return true;
		}
		if ((m = IF_NOT.matcher(line)).matches()) {
			parsed.add(new Instruction("ifnot", m.group(1).trim(), 0, lineNo));
			return true;
		}
		if ((m = IF_ANY.matcher(line)).matches()) {
			parsed.add(new Instruction("if", m.group(1).trim(), 0, lineNo));
			return true;
		}
		return false;
	}

	private void parseError(MinecraftClient client, int line, String what, String text) {
		chat(client, "§cLine " + line + ": " + what + " §f" + shorten(text));
	}

	private static String shorten(String text) {
		if (text == null) return "";
		return text.length() > 60 ? text.substring(0, 59) + "…" : text;
	}

	private static String describe(Throwable t) {
		String message = t.getMessage();
		return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}

	private static int safeInt(String raw, int fallback, int min, int max) {
		if (raw == null) return fallback;
		try {
			long value = Long.parseLong(raw.trim());
			return (int) Math.max(min, Math.min(max, value));
		} catch (Exception e) {
			return fallback;
		}
	}

	private GuiDef findGui(String title) {
		if (guis.isEmpty()) return null;
		if (title == null) return guis.get(guis.size() - 1);
		for (GuiDef def : guis) {
			if (def.title().equalsIgnoreCase(title)) return def;
		}
		return guis.get(guis.size() - 1);
	}

	// ------------------------------------------------------------------
	// tick
	// ------------------------------------------------------------------

	public void tick(MinecraftClient client) {
		try {
			tickInner(client);
		} catch (Throwable t) {
			// never let a script take the client tick loop down with it
			int line = pointer > 0 && pointer <= program.size() ? program.get(pointer - 1).line() : 0;
			chat(client, "§cScript §f" + scriptName + " §cerror on line " + line + ": §7" + describe(t));
			stop();
		}
	}

	private void tickInner(MinecraftClient client) {
		if (client == null) return;
		if (client.player == null || client.world == null) {
			inWorld = false;
			if (armed) {
				haltProgram();
			} else {
				stop();
			}
			return;
		}
		boolean joined = !inWorld;
		inWorld = true;
		engineTicks++;

		if (!heldKeys.isEmpty()) {
			var iterator = heldKeys.entrySet().iterator();
			while (iterator.hasNext()) {
				var entry = iterator.next();
				entry.setValue(entry.getValue() - 1);
				if (entry.getValue() <= 0) {
					try {
						entry.getKey().setPressed(false);
					} catch (Throwable ignored) {
					}
					iterator.remove();
				}
			}
		}

		if (!handlers.isEmpty()) {
			if (joined) fireKind("join", Map.of("event", "join"), null);
			pollEvents(client);
			if (!eventQueue.isEmpty() && isIdle()) {
				Pending next = eventQueue.poll();
				if (next != null) beginHandler(next.handler(), next.context());
			}
		}

		if (waitGuiTarget != null) {
			if (client.currentScreen instanceof HandledScreen<?> hs
					&& hs.getTitle().getString().toLowerCase(Locale.ROOT).contains(waitGuiTarget)) {
				waitGuiTarget = null;
			} else if (--waitGuiTimeout <= 0) {
				chat(client, "§cGUI never opened, continuing");
				waitGuiTarget = null;
			} else {
				return;
			}
		}
		if (waitTicks > 0) {
			waitTicks--;
			return;
		}
		if (pointer >= program.size()) return;

		int freeSteps = 0;
		long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
		boolean executed = false;
		while (pointer < program.size()) {
			Instruction inst = program.get(pointer++);
			executed = true;
			boolean costly = execute(client, inst);
			if (waitTicks > 0 || waitGuiTarget != null) break;
			if (costly) break;
			if (++freeSteps >= MAX_FREE_STEPS_PER_TICK || System.nanoTime() > deadline) break;
		}

		if (executed && pointer >= program.size() && !armed) {
			chat(client, "§7Script §f" + scriptName + " §afinished");
		}
	}

	/** @return true when this instruction consumes the rest of the tick. */
	private boolean execute(MinecraftClient client, Instruction inst) {
		String type = inst.type();

		if (type.startsWith("press:")) {
			net.minecraft.client.option.KeyBinding key = switch (type.substring(6)) {
				case "attack" -> client.options.attackKey;
				case "use" -> client.options.useKey;
				case "jump" -> client.options.jumpKey;
				default -> client.options.forwardKey;
			};
			try {
				key.setPressed(true);
				heldKeys.put(key, inst.ticks());
			} catch (Throwable ignored) {
			}
			return true;
		}

		switch (type) {
			case "wait" -> waitTicks = inst.ticks();
			case "cmd" -> {
				if (client.player != null) client.player.networkHandler.sendChatCommand(substitute(inst.arg()));
			}
			case "chat" -> {
				if (client.player != null) client.player.networkHandler.sendChatMessage(substitute(inst.arg()));
			}
			case "say" -> chat(client, "§f" + substitute(inst.arg()));
			case "loop" -> {
				if (frames.size() >= MAX_FRAMES) {
					abort(client, inst, "loop nesting deeper than " + MAX_FRAMES);
					return true;
				}
				long count = safeInt(inst.arg(), 1, 0, MAX_REPEAT);
				frames.push(new Frame("loop", pointer - 1, pointer, count - 1));
			}
			case "endloop", "endwhile" -> {
				Frame frame = frames.peek();
				if (frame != null) {
					if ("while".equals(frame.kind)) {
						pointer = frame.headerIndex;
					} else if (frame.remaining > 0) {
						frame.remaining--;
						frame.iterations++;
						pointer = frame.bodyStart;
					} else {
						frames.pop();
					}
				}
			}
			case "while" -> {
				int header = pointer - 1;
				Frame frame = frames.peek();
				if (frame == null || !"while".equals(frame.kind) || frame.headerIndex != header) {
					if (frames.size() >= MAX_FRAMES) {
						abort(client, inst, "loop nesting deeper than " + MAX_FRAMES);
						return true;
					}
					frame = new Frame("while", header, pointer, 0);
					frames.push(frame);
				} else {
					frame.iterations++;
				}
				if (frame.lastTick == engineTicks) {
					frame.spins++;
				} else {
					frame.lastTick = engineTicks;
					if (frame.spins > 0) frame.spins--;
				}
				boolean overrun = frame.iterations > MAX_WHILE_ITERATIONS
						|| frame.spins > MAX_WHILE_SPIN
						|| System.currentTimeMillis() - frame.startMs > MAX_WHILE_MILLIS;
				if (overrun) {
					chat(client, "§cLine " + inst.line() + ": while loop hit its safety cap - breaking out");
					frames.pop();
					pointer = Math.min(program.size(), matchingLoopEnd(frame.bodyStart) + 1);
					return true;
				}
				if (!evalCondition(client, inst.arg(), inst.line())) {
					frames.pop();
					pointer = Math.min(program.size(), matchingLoopEnd(frame.bodyStart) + 1);
				}
				return false;
			}
			case "break" -> {
				Frame frame = frames.poll();
				if (frame != null) {
					pointer = Math.min(program.size(), matchingLoopEnd(frame.bodyStart) + 1);
				}
			}
			case "clickslot" -> clickSlot(client, safeInt(inst.arg(), 0, 0, 4096), false);
			case "shiftclick" -> clickSlot(client, safeInt(inst.arg(), 0, 0, 4096), true);
			case "rightclick" -> rightClickSlot(client, safeInt(inst.arg(), 0, 0, 4096));
			case "closegui" -> {
				if (client.currentScreen != null && client.player != null) client.player.closeHandledScreen();
			}
			case "waitgui" -> {
				waitGuiTarget = inst.arg();
				waitGuiTimeout = 100;
			}
			case "sort" -> {
				if (client.currentScreen instanceof HandledScreen<?> hs
						&& ForgeOreCalculatorClient.INSTANCE != null) {
					ForgeOreCalculatorClient.INSTANCE.scriptSort(client, hs);
				}
			}
			case "sneak" -> {
				try {
					client.options.sneakKey.setPressed("on".equals(inst.arg()));
				} catch (Throwable ignored) {
				}
			}
			case "waitrandom" -> {
				String[] range = inst.arg().split(",");
				int min = safeInt(range[0], 0, 0, 1440000);
				int max = safeInt(range.length > 1 ? range[1] : range[0], min, min, 1440000);
				waitTicks = min + random.nextInt(Math.max(1, max - min + 1));
			}
			case "ifgui" -> doIf(guiTitleContains(client, inst.arg()));
			case "if" -> doIf(evalCondition(client, inst.arg(), inst.line()));
			case "ifnot" -> doIf(!evalCondition(client, inst.arg(), inst.line()));
			case "else" -> {
				// only reached by falling out of a taken branch
				pointer = Math.min(program.size(), matchingEndIf(pointer, false));
				return false;
			}
			case "endif" -> {
			}
			case "hotbar" -> {
				int index = safeInt(inst.arg(), 1, 1, 9) - 1;
				try {
					client.options.hotbarKeys[index].setPressed(true);
					heldKeys.put(client.options.hotbarKeys[index], 1);
				} catch (Throwable ignored) {
				}
			}
			case "drop" -> {
				try {
					client.options.dropKey.setPressed(true);
					heldKeys.put(client.options.dropKey, 1);
				} catch (Throwable ignored) {
				}
			}
			case "swaphands" -> {
				try {
					client.options.swapHandsKey.setPressed(true);
					heldKeys.put(client.options.swapHandsKey, 1);
				} catch (Throwable ignored) {
				}
			}
			case "openinv" -> {
				try {
					client.options.inventoryKey.setPressed(true);
					heldKeys.put(client.options.inventoryKey, 1);
				} catch (Throwable ignored) {
				}
			}
			case "look" -> {
				String[] angles = inst.arg().split(",");
				try {
					if (client.player != null) {
						client.player.setYaw(Float.parseFloat(angles[0]));
						client.player.setPitch(Math.max(-90, Math.min(90, Float.parseFloat(angles[1]))));
					}
				} catch (Throwable ignored) {
				}
			}
			case "notify" -> {
				if (ForgeOreCalculatorClient.INSTANCE != null) {
					ForgeOreCalculatorClient.INSTANCE.testPopup("§f" + substitute(inst.arg()));
				}
			}
			case "log" -> {
				String[] parts = split2(inst.arg());
				pushActivity(parts[0], substitute(parts[1]));
				return false;
			}
			case "set" -> {
				String[] parts = split2(inst.arg());
				vars.put(parts[0], evalExpression(client, parts[1], inst.line()));
				return false;
			}
			case "addvar" -> {
				String[] parts = split2(inst.arg());
				int delta = evalExpression(client, parts[1], inst.line());
				vars.merge(parts[0], delta, Integer::sum);
				return false;
			}
			case "stopscript" -> {
				pointer = program.size();
				chat(client, "§7Script §f" + scriptName + " §7stopped itself");
				if (armed) {
					stop();
					return true;
				}
			}
			case "halt" -> {
				pointer = program.size();
				if (armed && !armedAnnounced) {
					armedAnnounced = true;
					chat(client, "§7Script §f" + scriptName + " §7is armed §8("
							+ handlers.size() + " event" + (handlers.size() == 1 ? "" : "s") + ")");
				}
				return false;
			}
			case "endhandler" -> {
				frames.clear();
				Pending next = eventQueue.poll();
				if (next != null) {
					beginHandler(next.handler(), next.context());
				} else {
					pointer = program.size();
				}
				return false;
			}
			case "nop" -> {
				return false;
			}
			case "addguihud" -> {
				GuiDef target = findGui(inst.arg());
				if (target != null) {
					synchronized (ForgeOreCalculatorClient.HUD_GUIS) {
						ForgeOreCalculatorClient.HUD_GUIS.removeIf(g -> g.title().equalsIgnoreCase(target.title()));
						if (ForgeOreCalculatorClient.HUD_GUIS.size() < 3) {
							ForgeOreCalculatorClient.HUD_GUIS.add(target);
						}
					}
					chat(client, "§7GUI §f" + target.title() + " §7pinned to your HUD");
				} else {
					chat(client, "§cNo gui defined to pin");
				}
			}
			case "removeguihud" -> {
				String title = inst.arg();
				synchronized (ForgeOreCalculatorClient.HUD_GUIS) {
					if (title == null) {
						ForgeOreCalculatorClient.HUD_GUIS.clear();
					} else {
						ForgeOreCalculatorClient.HUD_GUIS.removeIf(g -> g.title().equalsIgnoreCase(title));
					}
				}
				chat(client, "§7HUD GUI removed");
			}
			case "clearbuttons" -> {
				ForgeOreCalculatorClient.EXTRA_BUTTONS.clear();
				ForgeOreCalculatorClient.saveExtraButtons();
			}
			case "addbutton" -> {
				String[] parts = inst.arg().split("\u0000");
				if (parts.length == 2) {
					synchronized (ForgeOreCalculatorClient.EXTRA_BUTTONS) {
						ForgeOreCalculatorClient.EXTRA_BUTTONS.removeIf(b -> b[0].equalsIgnoreCase(parts[0]));
						if (ForgeOreCalculatorClient.EXTRA_BUTTONS.size() < 6) {
							ForgeOreCalculatorClient.EXTRA_BUTTONS.add(parts);
						}
					}
					ForgeOreCalculatorClient.saveExtraButtons();
					chat(client, "§7Button §f" + parts[0] + " §7added to GUIs");
				}
			}
			case "showgui" -> {
				int index = safeInt(inst.arg(), 0, 0, Math.max(0, guis.size()));
				if (index < guis.size()) {
					client.setScreen(new ScriptGuiScreen(guis.get(index)));
				}
			}
			default -> {
				return false;
			}
		}
		return !FREE_OPS.contains(type);
	}

	private void abort(MinecraftClient client, Instruction inst, String why) {
		chat(client, "§cLine " + inst.line() + ": " + why + " - script stopped");
		stop();
	}

	/** Shared skip logic for {@code if} / {@code if not} / {@code if gui}. */
	private void doIf(boolean taken) {
		if (taken) return;
		int target = matchingEndIf(pointer, true);
		if (target < program.size() && "else".equals(program.get(target).type())) {
			pointer = target + 1;   // run the else body
		} else {
			pointer = Math.min(program.size(), target + 1);
		}
	}

	/**
	 * Index of the {@code else} (when {@code stopAtElse}) or {@code end if} that closes
	 * the block opened just before {@code from}. Returns {@code program.size()} when the
	 * block is unterminated, which is what the original engine did.
	 */
	private int matchingEndIf(int from, boolean stopAtElse) {
		int depth = 0;
		for (int i = from; i < program.size(); i++) {
			String type = program.get(i).type();
			switch (type) {
				case "if", "ifnot", "ifgui" -> depth++;
				case "endif" -> {
					if (depth == 0) return i;
					depth--;
				}
				case "else" -> {
					if (depth == 0 && stopAtElse) return i;
				}
				default -> {
				}
			}
		}
		return program.size();
	}

	/** Index of the {@code end loop} / {@code end while} closing the block at {@code from}. */
	private int matchingLoopEnd(int from) {
		int depth = 0;
		for (int i = from; i < program.size(); i++) {
			String type = program.get(i).type();
			switch (type) {
				case "loop", "while" -> depth++;
				case "endloop", "endwhile" -> {
					if (depth == 0) return i;
					depth--;
				}
				default -> {
				}
			}
		}
		return program.size();
	}

	// ------------------------------------------------------------------
	// events
	// ------------------------------------------------------------------

	/** The engine of the running client, or null. */
	public static ScriptEngine active() {
		try {
			return ForgeOreCalculatorClient.INSTANCE != null ? ForgeOreCalculatorClient.INSTANCE.scriptEngine : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Integrator hook: feed every received chat line here. Safe to call from anywhere. */
	public static void onChatLine(String raw) {
		ScriptEngine engine = active();
		if (engine != null) engine.handleChat(raw);
	}

	/** Integrator hook: call when the player joins a world (optional - also polled). */
	public static void onJoinWorld(MinecraftClient client) {
		ScriptEngine engine = active();
		if (engine != null && !engine.handlers.isEmpty()) {
			engine.fireKind("join", Map.of("event", "join"), null);
		}
	}

	/** Integrator hook: call when an item is picked up (optional - also polled). */
	public static void onItemPickup(ItemStack stack) {
		ScriptEngine engine = active();
		if (engine == null || stack == null || stack.isEmpty()) return;
		String name;
		try {
			name = strip(stack.getName().getString());
		} catch (Throwable t) {
			return;
		}
		engine.firePickup(name, stack.getCount());
	}

	private void handleChat(String raw) {
		if (raw == null || handlers.isEmpty()) return;
		String text = strip(raw);
		if (text.isEmpty()) return;
		for (Handler handler : handlers) {
			if (!"chat".equals(handler.kind) || handler.regex == null) continue;
			Matcher m = handler.regex.matcher(text);
			if (!m.find()) continue;
			Map<String, String> context = new LinkedHashMap<>();
			context.put("event", "chat");
			context.put("text", text);
			for (int g = 1; g <= Math.min(9, m.groupCount()); g++) {
				context.put(String.valueOf(g), m.group(g) == null ? "" : m.group(g));
			}
			dispatch(handler, context);
		}
	}

	private void firePickup(String itemName, int count) {
		if (handlers.isEmpty()) return;
		for (Handler handler : handlers) {
			if (!"pickup".equals(handler.kind)) continue;
			if (handler.arg != null && !handler.arg.isBlank()
					&& !itemName.toLowerCase(Locale.ROOT).contains(handler.arg.toLowerCase(Locale.ROOT))) {
				continue;
			}
			Map<String, String> context = new LinkedHashMap<>();
			context.put("event", "pickup");
			context.put("item", itemName);
			context.put("count", String.valueOf(count));
			vars.put("event_count", count);
			dispatch(handler, context);
		}
	}

	private void fireKind(String kind, Map<String, String> context, String titleFilter) {
		for (Handler handler : handlers) {
			if (!handler.kind.equals(kind)) continue;
			if (titleFilter != null && handler.arg != null && !handler.arg.isBlank()
					&& !titleFilter.toLowerCase(Locale.ROOT).contains(handler.arg.toLowerCase(Locale.ROOT))) {
				continue;
			}
			dispatch(handler, context);
		}
	}

	private void dispatch(Handler handler, Map<String, String> context) {
		if (isIdle()) {
			beginHandler(handler, context);
		} else if (eventQueue.size() < MAX_EVENT_QUEUE) {
			eventQueue.add(new Pending(handler, context));
		}
	}

	private boolean isIdle() {
		return pointer >= program.size() && waitTicks <= 0 && waitGuiTarget == null;
	}

	private void beginHandler(Handler handler, Map<String, String> context) {
		eventVars.clear();
		if (context != null) eventVars.putAll(context);
		frames.clear();
		pointer = handler.start;
	}

	private void pollEvents(MinecraftClient client) {
		// ---- gui open / close ----
		String title = null;
		if (client.currentScreen instanceof HandledScreen<?> hs) {
			try {
				title = hs.getTitle().getString();
			} catch (Throwable t) {
				title = "";
			}
		}
		if (watchGui && !Objects.equals(title, lastGuiTitle)) {
			String previous = lastGuiTitle;
			if (previous != null) {
				fireKind("guiclose", Map.of("event", "guiclose", "title", previous), null);
			}
			if (title != null) {
				fireKind("guiopen", Map.of("event", "guiopen", "title", title), title);
			}
		}
		lastGuiTitle = title;

		// ---- slot change ----
		if (watchSlots) {
			pollSlots(client);
		} else {
			slotSnapshot = null;
		}

		// ---- item pickup ----
		if (watchPickup) {
			pollPickups(client);
		} else {
			pickupSnapshot = null;
		}

		// ---- tick interval ----
		for (Handler handler : handlers) {
			if (!"interval".equals(handler.kind)) continue;
			if (++handler.counter >= handler.interval) {
				handler.counter = 0;
				dispatch(handler, Map.of("event", "interval"));
			}
		}
	}

	private void pollSlots(MinecraftClient client) {
		if (!(client.currentScreen instanceof HandledScreen<?> hs)) {
			slotSnapshot = null;
			slotSyncId = Integer.MIN_VALUE;
			return;
		}
		var handler = hs.getScreenHandler();
		int size = handler.slots.size();
		if (slotSnapshot == null || slotSnapshot.length != size || handler.syncId != slotSyncId) {
			slotSyncId = handler.syncId;
			slotSnapshot = new String[size];
			for (int i = 0; i < size; i++) slotSnapshot[i] = stackKey(handler.slots.get(i).getStack());
			return;
		}
		for (int i = 0; i < size; i++) {
			String key = stackKey(handler.slots.get(i).getStack());
			if (key.equals(slotSnapshot[i])) continue;
			slotSnapshot[i] = key;
			final int slot = i;
			vars.put("event_slot", slot);
			for (Handler h : handlers) {
				if (!"slotchange".equals(h.kind)) continue;
				if (h.slot >= 0 && h.slot != slot) continue;
				dispatch(h, Map.of("event", "slotchange", "slot", String.valueOf(slot)));
			}
		}
	}

	private void pollPickups(MinecraftClient client) {
		Map<String, Integer> counts = new HashMap<>();
		try {
			var inv = client.player.getInventory();
			int size = Math.min(36, inv.size());
			for (int i = 0; i < size; i++) {
				ItemStack stack = inv.getStack(i);
				if (stack.isEmpty()) continue;
				counts.merge(strip(stack.getName().getString()), stack.getCount(), Integer::sum);
			}
		} catch (Throwable t) {
			return;
		}
		if (pickupSnapshot == null) {
			pickupSnapshot = counts;
			return;
		}
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			int before = pickupSnapshot.getOrDefault(entry.getKey(), 0);
			if (entry.getValue() > before) {
				firePickup(entry.getKey(), entry.getValue() - before);
			}
		}
		pickupSnapshot = counts;
	}

	private static String stackKey(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return "";
		try {
			return stack.getName().getString() + "x" + stack.getCount();
		} catch (Throwable t) {
			return "?";
		}
	}

	// ------------------------------------------------------------------
	// conditions
	// ------------------------------------------------------------------

	private boolean evalCondition(MinecraftClient client, String raw, int line) {
		try {
			return condition(client, raw == null ? "" : raw.trim(), 0);
		} catch (Throwable t) {
			chat(client, "§cLine " + line + ": bad condition §f" + shorten(raw));
			return false;
		}
	}

	private boolean condition(MinecraftClient client, String raw, int depth) {
		String text = raw.trim();
		if (text.isEmpty() || depth > 8) return false;

		List<String> ors = splitTop(text, " or ");
		if (ors.size() > 1) {
			for (String part : ors) {
				if (condition(client, part, depth + 1)) return true;
			}
			return false;
		}
		List<String> ands = splitTop(text, " and ");
		if (ands.size() > 1) {
			for (String part : ands) {
				if (!condition(client, part, depth + 1)) return false;
			}
			return true;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.startsWith("not ")) return !condition(client, text.substring(4), depth + 1);
		if (text.startsWith("(") && text.endsWith(")") && balanced(text)) {
			return condition(client, text.substring(1, text.length() - 1), depth + 1);
		}
		if (lower.equals("true")) return true;
		if (lower.equals("false")) return false;

		Matcher m;
		if ((m = C_GUI_TITLE.matcher(text)).matches()) return guiTitleContains(client, m.group(1));
		if ((m = C_GUI.matcher(text)).matches()) return guiTitleContains(client, m.group(1));
		if (C_GUI_OPEN.matcher(text).matches()) return client != null && client.currentScreen instanceof HandledScreen<?>;
		if (C_NO_GUI.matcher(text).matches()) return client == null || !(client.currentScreen instanceof HandledScreen<?>);
		if ((m = C_SLOT_HAS.matcher(text)).matches()) {
			return itemMatches(stackInSlot(client, safeInt(m.group(1), -1, 0, 4096)), m.group(2));
		}
		if ((m = C_SLOT_EMPTY.matcher(text)).matches()) {
			ItemStack stack = stackInSlot(client, safeInt(m.group(1), -1, 0, 4096));
			return stack == null || stack.isEmpty();
		}
		if ((m = C_SLOT_FILLED.matcher(text)).matches()) {
			ItemStack stack = stackInSlot(client, safeInt(m.group(1), -1, 0, 4096));
			return stack != null && !stack.isEmpty();
		}
		if (C_FREE.matcher(text).matches()) return freeSlots(client) > 0;
		if (C_INV_FULL.matcher(text).matches()) return freeSlots(client) == 0;
		if ((m = C_HEALTH.matcher(text)).matches()) {
			float health = client != null && client.player != null ? client.player.getHealth() : 0f;
			float target = safeFloat(m.group(2));
			return isAbove(m.group(1)) ? health > target : health < target;
		}
		if ((m = C_HUNGER.matcher(text)).matches()) {
			float food = 0f;
			try {
				if (client != null && client.player != null) food = client.player.getHungerManager().getFoodLevel();
			} catch (Throwable ignored) {
			}
			float target = safeFloat(m.group(2));
			return isAbove(m.group(1)) ? food > target : food < target;
		}
		if ((m = C_HAS_ITEM.matcher(text)).matches()) return inventoryCount(client, m.group(1)) > 0;

		int operator = topLevelOperator(text);
		if (operator >= 0) {
			m = C_COMPARE.matcher(text);
			if (m.matches()) {
				int left = evalExpression(client, m.group(1), 0);
				int right = evalExpression(client, m.group(3), 0);
				return switch (m.group(2)) {
					case "==", "=" -> left == right;
					case "!=" -> left != right;
					case "<" -> left < right;
					case ">" -> left > right;
					case "<=" -> left <= right;
					case ">=" -> left >= right;
					default -> false;
				};
			}
		}
		return evalExpression(client, text, 0) != 0;
	}

	private static boolean isAbove(String word) {
		String w = word.toLowerCase(Locale.ROOT);
		return w.equals("above") || w.equals("over");
	}

	private static float safeFloat(String raw) {
		try {
			return Float.parseFloat(raw.trim());
		} catch (Exception e) {
			return 0f;
		}
	}

	private boolean guiTitleContains(MinecraftClient client, String needle) {
		if (client == null || needle == null) return false;
		if (!(client.currentScreen instanceof HandledScreen<?> hs)) return false;
		try {
			return hs.getTitle().getString().toLowerCase(Locale.ROOT)
					.contains(needle.toLowerCase(Locale.ROOT));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Splits on a keyword that sits outside quotes and outside parentheses. */
	private static List<String> splitTop(String text, String token) {
		List<String> out = new ArrayList<>();
		boolean quoted = false;
		int depth = 0;
		int start = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') quoted = !quoted;
			else if (!quoted && c == '(') depth++;
			else if (!quoted && c == ')') depth--;
			else if (!quoted && depth == 0 && text.regionMatches(true, i, token, 0, token.length())) {
				out.add(text.substring(start, i));
				i += token.length() - 1;
				start = i + 1;
			}
		}
		out.add(text.substring(start));
		if (out.size() == 1) return out;
		out.removeIf(String::isBlank);
		return out;
	}

	private static boolean balanced(String text) {
		int depth = 0;
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') quoted = !quoted;
			else if (!quoted && c == '(') depth++;
			else if (!quoted && c == ')') {
				depth--;
				if (depth == 0 && i != text.length() - 1) return false;
			}
		}
		return depth == 0;
	}

	private static int topLevelOperator(String text) {
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') quoted = !quoted;
			if (quoted) continue;
			if (c == '=' || c == '!' || c == '<' || c == '>') return i;
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// expressions
	// ------------------------------------------------------------------

	private static final class Lexer {
		final String source;
		int index;

		Lexer(String source) {
			this.source = source == null ? "" : source;
		}

		void skip() {
			while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
		}

		boolean word(String expected) {
			skip();
			if (!source.regionMatches(true, index, expected, 0, expected.length())) return false;
			int after = index + expected.length();
			if (after < source.length()) {
				char c = source.charAt(after);
				if (Character.isLetterOrDigit(c) || c == '_') return false;
			}
			index = after;
			return true;
		}

		boolean symbol(char expected) {
			skip();
			if (index < source.length() && source.charAt(index) == expected) {
				index++;
				return true;
			}
			return false;
		}

		char peek() {
			skip();
			return index < source.length() ? source.charAt(index) : '\0';
		}

		String identifier() {
			skip();
			int start = index;
			while (index < source.length()
					&& (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_')) {
				index++;
			}
			return source.substring(start, index);
		}

		String quoted() {
			skip();
			if (index >= source.length() || source.charAt(index) != '"') return null;
			int start = ++index;
			while (index < source.length() && source.charAt(index) != '"') index++;
			String value = source.substring(start, index);
			if (index < source.length()) index++;
			return value;
		}
	}

	private int evalExpression(MinecraftClient client, String raw, int line) {
		try {
			Lexer lexer = new Lexer(raw == null ? "" : raw.trim());
			int value = sum(client, lexer, 0);
			return value;
		} catch (Throwable t) {
			if (line > 0) chat(client, "§cLine " + line + ": bad expression §f" + shorten(raw));
			return 0;
		}
	}

	private int sum(MinecraftClient client, Lexer lexer, int depth) {
		int value = term(client, lexer, depth);
		while (true) {
			char c = lexer.peek();
			if (c == '+') {
				lexer.symbol('+');
				value += term(client, lexer, depth);
			} else if (c == '-') {
				lexer.symbol('-');
				value -= term(client, lexer, depth);
			} else {
				return value;
			}
		}
	}

	private int term(MinecraftClient client, Lexer lexer, int depth) {
		int value = unary(client, lexer, depth);
		while (true) {
			char c = lexer.peek();
			if (c == '*') {
				lexer.symbol('*');
				value *= unary(client, lexer, depth);
			} else if (c == '/') {
				lexer.symbol('/');
				int divisor = unary(client, lexer, depth);
				value = divisor == 0 ? 0 : value / divisor;
			} else if (c == '%') {
				lexer.symbol('%');
				int divisor = unary(client, lexer, depth);
				value = divisor == 0 ? 0 : value % divisor;
			} else {
				return value;
			}
		}
	}

	private int unary(MinecraftClient client, Lexer lexer, int depth) {
		if (lexer.symbol('-')) return -unary(client, lexer, depth);
		if (lexer.symbol('+')) return unary(client, lexer, depth);
		return primary(client, lexer, depth);
	}

	private int primary(MinecraftClient client, Lexer lexer, int depth) {
		if (depth > 12) return 0;
		if (lexer.symbol('(')) {
			int value = sum(client, lexer, depth + 1);
			lexer.symbol(')');
			return value;
		}
		char c = lexer.peek();
		if (Character.isDigit(c)) {
			int start = lexer.index;
			while (lexer.index < lexer.source.length() && Character.isDigit(lexer.source.charAt(lexer.index))) {
				lexer.index++;
			}
			return safeInt(lexer.source.substring(start, lexer.index), 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
		}
		if (lexer.word("random")) {
			int min = sum(client, lexer, depth + 1);
			lexer.word("to");
			int max = sum(client, lexer, depth + 1);
			if (max < min) {
				int swap = min;
				min = max;
				max = swap;
			}
			long span = (long) max - min + 1L;
			return min + (int) (random.nextDouble() * Math.max(1L, span));
		}
		if (lexer.word("health")) {
			return client != null && client.player != null ? (int) client.player.getHealth() : 0;
		}
		if (lexer.word("hunger") || lexer.word("food")) {
			try {
				return client != null && client.player != null ? client.player.getHungerManager().getFoodLevel() : 0;
			} catch (Throwable t) {
				return 0;
			}
		}
		if (lexer.word("free")) {
			lexer.word("slots");
			lexer.word("slot");
			lexer.word("space");
			return freeSlots(client);
		}
		if (lexer.word("count")) {
			String item = lexer.quoted();
			return inventoryCount(client, item);
		}
		if (lexer.word("gui")) {
			lexer.word("count");
			String item = lexer.quoted();
			return guiCount(client, item);
		}
		if (lexer.word("slot")) {
			lexer.word("count");
			String item = lexer.quoted();
			if (item != null) return guiCount(client, item);
			int slot = sum(client, lexer, depth + 1);
			lexer.word("count");
			ItemStack stack = stackInSlot(client, slot);
			return stack == null || stack.isEmpty() ? 0 : stack.getCount();
		}
		String name = lexer.identifier();
		if (name.isEmpty()) {
			lexer.index++;   // never spin on an unexpected character
			return 0;
		}
		Integer value = vars.get(name);
		if (value != null) return value;
		String event = eventVars.get(name);
		if (event != null) return safeInt(event, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
		return 0;
	}

	// ------------------------------------------------------------------
	// world helpers - every one no-ops when the player or screen is gone
	// ------------------------------------------------------------------

	private ItemStack stackInSlot(MinecraftClient client, int slot) {
		if (client == null || slot < 0) return ItemStack.EMPTY;
		try {
			if (client.currentScreen instanceof HandledScreen<?> hs) {
				var handler = hs.getScreenHandler();
				if (slot < handler.slots.size()) return handler.slots.get(slot).getStack();
				return ItemStack.EMPTY;
			}
			if (client.player == null) return ItemStack.EMPTY;
			var inv = client.player.getInventory();
			return slot < inv.size() ? inv.getStack(slot) : ItemStack.EMPTY;
		} catch (Throwable t) {
			return ItemStack.EMPTY;
		}
	}

	private int freeSlots(MinecraftClient client) {
		if (client == null || client.player == null) return 0;
		try {
			var inv = client.player.getInventory();
			int size = Math.min(36, inv.size());
			int free = 0;
			for (int i = 0; i < size; i++) {
				if (inv.getStack(i).isEmpty()) free++;
			}
			return free;
		} catch (Throwable t) {
			return 0;
		}
	}

	private int inventoryCount(MinecraftClient client, String item) {
		if (client == null || client.player == null || item == null) return 0;
		try {
			var inv = client.player.getInventory();
			int size = Math.min(36, inv.size());
			int total = 0;
			for (int i = 0; i < size; i++) {
				ItemStack stack = inv.getStack(i);
				if (itemMatches(stack, item)) total += stack.getCount();
			}
			return total;
		} catch (Throwable t) {
			return 0;
		}
	}

	private int guiCount(MinecraftClient client, String item) {
		if (client == null || item == null) return 0;
		if (!(client.currentScreen instanceof HandledScreen<?> hs)) return inventoryCount(client, item);
		try {
			int total = 0;
			var handler = hs.getScreenHandler();
			for (int i = 0; i < handler.slots.size(); i++) {
				ItemStack stack = handler.slots.get(i).getStack();
				if (itemMatches(stack, item)) total += stack.getCount();
			}
			return total;
		} catch (Throwable t) {
			return 0;
		}
	}

	private static boolean itemMatches(ItemStack stack, String query) {
		if (stack == null || stack.isEmpty() || query == null) return false;
		String needle = strip(query).toLowerCase(Locale.ROOT).trim();
		if (needle.isEmpty()) return false;
		try {
			if (strip(stack.getName().getString()).toLowerCase(Locale.ROOT).contains(needle)) return true;
		} catch (Throwable ignored) {
		}
		try {
			String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString()
					.toLowerCase(Locale.ROOT);
			if (id.contains(needle) || id.replace('_', ' ').contains(needle)) return true;
		} catch (Throwable ignored) {
		}
		return false;
	}

	private void clickSlot(MinecraftClient client, int slot, boolean shift) {
		if (client == null || client.player == null) return;
		if (client.currentScreen instanceof HandledScreen<?> hs
				&& ForgeOreCalculatorClient.INSTANCE != null) {
			ForgeOreCalculatorClient.INSTANCE.scriptClick(hs, slot, shift);
		} else {
			chat(client, "§cNo GUI open for click slot " + slot);
		}
	}

	private void rightClickSlot(MinecraftClient client, int slot) {
		if (client == null || client.player == null || client.interactionManager == null) return;
		if (!(client.currentScreen instanceof HandledScreen<?> hs)) {
			chat(client, "§cNo GUI open for right click slot " + slot);
			return;
		}
		try {
			var handler = hs.getScreenHandler();
			if (slot < 0 || slot >= handler.slots.size()) return;
			client.interactionManager.clickSlot(handler.syncId, slot, 1, SlotActionType.PICKUP, client.player);
		} catch (Throwable ignored) {
		}
	}

	private void pushActivity(String kindName, String text) {
		try {
			LiveActivity.Kind kind = LiveActivity.Kind.byName(kindName);
			LiveActivity.push(kind == null ? LiveActivity.Kind.SYSTEM : kind, text);
		} catch (Throwable ignored) {
		}
	}

	// ------------------------------------------------------------------
	// text helpers
	// ------------------------------------------------------------------

	private static String[] split2(String arg) {
		if (arg == null) return new String[]{"", ""};
		int split = arg.indexOf('\u0000');
		if (split < 0) return new String[]{arg, ""};
		return new String[]{arg.substring(0, split), arg.substring(split + 1)};
	}

	public static String strip(String raw) {
		if (raw == null) return "";
		return COLOR_CODES.matcher(raw).replaceAll("").trim();
	}

	/**
	 * Replaces {@code %name%} with a variable or event value. Unknown names are left
	 * untouched, so text from older scripts can never be mangled.
	 */
	private String substitute(String text) {
		if (text == null || text.indexOf('%') < 0) return text;
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '%') {
				int end = text.indexOf('%', i + 1);
				if (end > i + 1) {
					String key = text.substring(i + 1, end);
					String value = lookup(key);
					if (value != null) {
						out.append(value);
						i = end + 1;
						continue;
					}
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private String lookup(String key) {
		if (key == null || key.isEmpty()) return null;
		String value = eventVars.get(key);
		if (value != null) return value;
		value = eventVars.get(key.toLowerCase(Locale.ROOT));
		if (value != null) return value;
		Integer number = vars.get(key);
		return number == null ? null : String.valueOf(number);
	}

	private void chat(MinecraftClient client, String msg) {
		if (client != null && client.player != null && ForgeOreCalculatorClient.INSTANCE != null) {
			ForgeOreCalculatorClient.INSTANCE.notifyChat(client, msg);
		}
	}
}
