package com.example;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Scripting 2.0" - the block model behind {@link BlockScriptScreen}.
 *
 * <p>This is NOT a second runtime. Every block compiles down to a line of the
 * existing {@link ScriptEngine} text vocabulary, and {@link #decompile(List)}
 * parses that vocabulary back into blocks. Any line the parser does not
 * understand becomes a {@code raw} block that stores the original text
 * verbatim and emits it again byte-for-byte, so opening and re-saving an old
 * script can never damage it.</p>
 *
 * <p>A handful of blocks the task asks for have no command in ScriptEngine yet
 * (chat/gui-open events, slot-content tests, free-space tests, else). Those are
 * marked {@code engineSupported=false}; the editor badges them and
 * {@link #warnings(List)} lists them. They still compile to the command string
 * the engine would need, so adding the regex later is the only work left.</p>
 */
public final class BlockScript {

	private BlockScript() {
	}

	// ------------------------------------------------------------------
	// model
	// ------------------------------------------------------------------

	public enum ParamType {INT, STRING, SLOT, ITEM, DIRECTION, BOOLEAN}

	public enum Category {
		EVENTS("EVENTS", 0xFFFFBF00),
		CONTROL("CONTROL", 0xFFFFAB19),
		GUI("GUI", 0xFF9966FF),
		CHAT("CHAT", 0xFFCF63CF),
		LOGIC("LOGIC", 0xFF59C059),
		PLAYER("PLAYER", 0xFF4C97FF),
		RAW("RAW", 0xFF8A8A8A);

		public final String label;
		public final int color;

		Category(String label, int color) {
			this.label = label;
			this.color = color;
		}
	}

	/** Direction/key choices for the DIRECTION parameter type. */
	public static final String[] DIRECTIONS = {"forward", "attack", "use", "jump"};

	public static final class ParamDef {
		public final String name;
		public final ParamType type;
		public final String defaultValue;

		public ParamDef(String name, ParamType type, String defaultValue) {
			this.name = name;
			this.type = type;
			this.defaultValue = defaultValue;
		}
	}

	public static final class BlockDef {
		public final String type;
		public final Category category;
		/** Display label; {@code %1}, {@code %2}, ... are replaced by parameter values. */
		public final String label;
		public final List<ParamDef> params;
		public final boolean container;
		public final boolean hasElse;
		public final boolean engineSupported;
		/** When unsupported, the command string ScriptEngine would have to learn. */
		public final String needsCommand;

		BlockDef(String type, Category category, String label, boolean container, boolean hasElse,
				 boolean engineSupported, String needsCommand, ParamDef... params) {
			this.type = type;
			this.category = category;
			this.label = label;
			this.container = container;
			this.hasElse = hasElse;
			this.engineSupported = engineSupported;
			this.needsCommand = needsCommand;
			this.params = List.of(params);
		}
	}

	/** One node of the script tree. */
	public static final class Block {
		public String type;
		public final List<String> params = new ArrayList<>();
		public final List<Block> children = new ArrayList<>();
		public final List<Block> elseChildren = new ArrayList<>();

		public Block(String type) {
			this.type = type;
		}

		public String param(int index) {
			return index >= 0 && index < params.size() ? params.get(index) : "";
		}

		public void setParam(int index, String value) {
			while (params.size() <= index) params.add("");
			params.set(index, value == null ? "" : value);
		}
	}

	/** A saved script: a name plus its root block list. */
	public static final class Doc {
		public String name;
		public long updated;
		public final List<Block> blocks = new ArrayList<>();

		public Doc(String name) {
			this.name = name;
			this.updated = System.currentTimeMillis();
		}
	}

	// ------------------------------------------------------------------
	// catalogue
	// ------------------------------------------------------------------

	private static final Map<String, BlockDef> DEFS = new LinkedHashMap<>();

	private static void def(String type, Category cat, String label, ParamDef... params) {
		DEFS.put(type, new BlockDef(type, cat, label, false, false, true, null, params));
	}

	private static void defC(String type, Category cat, String label, ParamDef... params) {
		DEFS.put(type, new BlockDef(type, cat, label, true, false, true, null, params));
	}

	private static void defUnsupported(String type, Category cat, String label, boolean container,
									   boolean hasElse, String needs, ParamDef... params) {
		DEFS.put(type, new BlockDef(type, cat, label, container, hasElse, false, needs, params));
	}

	private static ParamDef p(String name, ParamType type, String def) {
		return new ParamDef(name, type, def);
	}

	static {
		// ---- EVENTS -------------------------------------------------
		def("event_join", Category.EVENTS, "when I join a world");
		// the engine gained real event handlers (EV_CHAT / EV_GUI_OPEN), so these are
		// containers that emit "on ..." / "end on" rather than dead comments
		defC("event_chat", Category.EVENTS, "when chat contains %1",
				p("text", ParamType.STRING, "welcome"));
		defC("event_gui_open", Category.EVENTS, "when a GUI titled %1 opens",
				p("title", ParamType.STRING, "Menu"));

		// ---- CONTROL ------------------------------------------------
		defC("repeat", Category.CONTROL, "repeat %1 times", p("times", ParamType.INT, "3"));
		defC("repeat_forever", Category.CONTROL, "repeat forever");
		def("wait_ticks", Category.CONTROL, "wait %1 ticks", p("ticks", ParamType.INT, "20"));
		def("wait_sec", Category.CONTROL, "wait %1 seconds", p("seconds", ParamType.INT, "2"));
		def("wait_random", Category.CONTROL, "wait %1 to %2 sec (random)",
				p("min", ParamType.INT, "2"), p("max", ParamType.INT, "5"));
		defUnsupported("if_else", Category.CONTROL, "if GUI title has %1 ... else", true, true,
				"if not gui \"...\"", p("title", ParamType.STRING, "Menu"));
		def("stop", Category.CONTROL, "stop this script");

		// ---- GUI ----------------------------------------------------
		def("gui_open_cmd", Category.GUI, "open GUI with command %1", p("command", ParamType.STRING, "/menu"));
		def("wait_for_gui", Category.GUI, "wait for GUI titled %1", p("title", ParamType.STRING, "Menu"));
		def("click_slot", Category.GUI, "click slot %1", p("slot", ParamType.SLOT, "0"));
		def("shift_click_slot", Category.GUI, "shift-click slot %1", p("slot", ParamType.SLOT, "0"));
		def("close_gui", Category.GUI, "close the GUI");
		def("sort_gui", Category.GUI, "sort this GUI");

		// ---- CHAT ---------------------------------------------------
		def("send_message", Category.CHAT, "send message %1", p("message", ParamType.STRING, "hello"));
		def("send_command", Category.CHAT, "send command %1", p("command", ParamType.STRING, "/fly"));
		def("say_local", Category.CHAT, "say %1 (only me)", p("message", ParamType.STRING, "hi"));
		def("notify", Category.CHAT, "popup %1", p("message", ParamType.STRING, "done!"));

		// ---- LOGIC (C-shaped conditions) ----------------------------
		defC("if_gui", Category.LOGIC, "if GUI title contains %1", p("title", ParamType.STRING, "Menu"));
		defUnsupported("if_slot_item", Category.LOGIC, "if slot %1 contains %2", true, false,
				"if slot N has \"...\"", p("slot", ParamType.SLOT, "0"), p("item", ParamType.ITEM, "Diamond"));
		defUnsupported("if_free_space", Category.LOGIC, "if inventory has free space", true, false,
				"if free space");

		// ---- PLAYER -------------------------------------------------
		def("press_key", Category.PLAYER, "hold %1 for %2 ticks",
				p("key", ParamType.DIRECTION, "forward"), p("ticks", ParamType.INT, "20"));
		def("sneak", Category.PLAYER, "sneak %1", p("on", ParamType.BOOLEAN, "true"));
		def("hotbar", Category.PLAYER, "select hotbar slot %1", p("slot", ParamType.INT, "1"));
		def("look", Category.PLAYER, "look yaw %1 pitch %2",
				p("yaw", ParamType.INT, "0"), p("pitch", ParamType.INT, "0"));
		def("drop", Category.PLAYER, "drop held item");
		def("swap_hands", Category.PLAYER, "swap hands");
		def("open_inventory", Category.PLAYER, "open inventory");

		// ---- RAW ----------------------------------------------------
		def("raw", Category.RAW, "raw: %1", p("line", ParamType.STRING, ""));
	}

	public static BlockDef def(String type) {
		return DEFS.get(type);
	}

	/** Every palette entry, in catalogue order (category order is preserved). */
	public static List<BlockDef> palette() {
		return new ArrayList<>(DEFS.values());
	}

	public static Block create(String type) {
		Block block = new Block(type);
		BlockDef def = DEFS.get(type);
		if (def != null) {
			for (ParamDef param : def.params) block.params.add(param.defaultValue);
		}
		return block;
	}

	public static Block raw(String line) {
		Block block = new Block("raw");
		block.params.add(line == null ? "" : line);
		return block;
	}

	/** Label with %1/%2 filled in from the block's values, for the canvas. */
	public static String labelOf(Block block) {
		BlockDef def = DEFS.get(block.type);
		if (def == null) return "§c? " + block.type;
		if ("raw".equals(block.type)) {
			String line = block.param(0);
			return line.isBlank() ? "§8(blank line)" : "§7" + line.trim();
		}
		String out = def.label;
		for (int i = 0; i < def.params.size(); i++) {
			out = out.replace("%" + (i + 1), "§e" + shorten(block.param(i)) + "§f");
		}
		return "§f" + out;
	}

	/** Label with %1/%2 shown as placeholder names, for the palette. */
	public static String paletteLabelOf(BlockDef def) {
		String out = def.label;
		for (int i = 0; i < def.params.size(); i++) {
			out = out.replace("%" + (i + 1), "§7[" + def.params.get(i).name + "]§f");
		}
		return "§f" + out;
	}

	private static String shorten(String value) {
		if (value == null) return "";
		String out = value.replace("\n", " ");
		return out.length() > 22 ? out.substring(0, 21) + "…" : out;
	}

	// ------------------------------------------------------------------
	// compile: blocks -> ScriptEngine text
	// ------------------------------------------------------------------

	public static List<String> compile(List<Block> blocks) {
		List<String> out = new ArrayList<>();
		emit(blocks, out, 0);
		return out;
	}

	private static void emit(List<Block> blocks, List<String> out, int depth) {
		String pad = "  ".repeat(Math.max(0, depth));
		for (Block block : blocks) {
			BlockDef def = DEFS.get(block.type);
			if (def == null) {
				out.add(pad + "# unknown block " + block.type);
				continue;
			}
			switch (block.type) {
				// raw lines go out exactly as they came in - this is what keeps
				// the round-trip lossless for anything we do not understand
				case "raw" -> out.add(block.param(0));

				case "event_join" -> out.add(pad + "on join");
				case "event_chat" -> {
					out.add(pad + "on chat match \"" + text(block, 0) + "\"");
					emit(block.children, out, depth + 1);
					out.add(pad + "end on");
				}
				case "event_gui_open" -> {
					out.add(pad + "on gui open \"" + text(block, 0) + "\"");
					emit(block.children, out, depth + 1);
					out.add(pad + "end on");
				}

				case "repeat" -> {
					out.add(pad + "loop " + num(block, 0, 1) + " times");
					emit(block.children, out, depth + 1);
					out.add(pad + "end loop");
				}
				case "repeat_forever" -> {
					out.add(pad + "loop forever");
					emit(block.children, out, depth + 1);
					out.add(pad + "end loop");
				}
				case "wait_ticks" -> out.add(pad + "wait " + num(block, 0, 1) + " ticks");
				case "wait_sec" -> out.add(pad + "wait " + num(block, 0, 1) + " seconds");
				case "wait_random" -> {
					int min = num(block, 0, 1);
					int max = Math.max(min, num(block, 1, 1));
					out.add(pad + "wait random " + min + " to " + max + " sec");
				}
				case "stop" -> out.add(pad + "stop");

				case "if_gui" -> {
					out.add(pad + "if gui \"" + text(block, 0) + "\"");
					emit(block.children, out, depth + 1);
					out.add(pad + "end if");
				}
				case "if_else" -> {
					out.add(pad + "if gui \"" + text(block, 0) + "\"");
					emit(block.children, out, depth + 1);
					out.add(pad + "end if");
					out.add(pad + "if not gui \"" + text(block, 0) + "\"");
					emit(block.elseChildren, out, depth + 1);
					out.add(pad + "end if");
				}
				case "if_slot_item" -> {
					out.add(pad + "if slot " + num(block, 0, 0) + " has \"" + text(block, 1) + "\"");
					emit(block.children, out, depth + 1);
					out.add(pad + "end if");
				}
				case "if_free_space" -> {
					out.add(pad + "if free space");
					emit(block.children, out, depth + 1);
					out.add(pad + "end if");
				}

				case "gui_open_cmd", "send_command" -> out.add(pad + "run command \"/" + command(block) + "\"");
				case "wait_for_gui" -> out.add(pad + "wait for gui \"" + text(block, 0) + "\"");
				case "click_slot" -> out.add(pad + "click slot " + num(block, 0, 0));
				case "shift_click_slot" -> out.add(pad + "shift click slot " + num(block, 0, 0));
				case "close_gui" -> out.add(pad + "close gui");
				case "sort_gui" -> out.add(pad + "sort");

				case "send_message" -> out.add(pad + "send \"" + text(block, 0) + "\"");
				case "say_local" -> out.add(pad + "say \"" + text(block, 0) + "\"");
				case "notify" -> out.add(pad + "notify \"" + text(block, 0) + "\"");

				case "press_key" -> out.add(pad + "press " + direction(block, 0) + " for " + num(block, 1, 1) + " ticks");
				case "sneak" -> out.add(pad + "sneak " + (bool(block, 0) ? "on" : "off"));
				case "hotbar" -> out.add(pad + "hotbar " + Math.max(1, Math.min(9, num(block, 0, 1))));
				case "look" -> out.add(pad + "look " + num(block, 0, 0) + " " + num(block, 1, 0));
				case "drop" -> out.add(pad + "drop");
				case "swap_hands" -> out.add(pad + "swap hands");
				case "open_inventory" -> out.add(pad + "open inventory");

				default -> out.add(pad + "# unhandled block " + block.type);
			}
		}
	}

	/** ScriptEngine's quoted-argument regexes cannot survive an embedded quote. */
	private static String text(Block block, int index) {
		return block.param(index).replace("\"", "'").replace("\n", " ").trim();
	}

	private static String command(Block block) {
		String raw = text(block, 0);
		while (raw.startsWith("/")) raw = raw.substring(1);
		return raw;
	}

	private static int num(Block block, int index, int min) {
		try {
			return Math.max(min, Integer.parseInt(block.param(index).trim()));
		} catch (Exception ignored) {
			return min;
		}
	}

	private static boolean bool(Block block, int index) {
		return "true".equalsIgnoreCase(block.param(index).trim());
	}

	private static String direction(Block block, int index) {
		String value = block.param(index).trim().toLowerCase(Locale.ROOT);
		for (String option : DIRECTIONS) {
			if (option.equals(value)) return option;
		}
		return DIRECTIONS[0];
	}

	// ------------------------------------------------------------------
	// decompile: ScriptEngine text -> blocks
	// ------------------------------------------------------------------

	private static final int FLAGS = Pattern.CASE_INSENSITIVE;
	private static final Pattern D_JOIN = Pattern.compile("^on\\s+join$", FLAGS);
	// the '#' is optional: scripts saved before these events were live still carry the
	// commented-out form, and must keep round-tripping
	private static final Pattern D_EV_CHAT = Pattern.compile("^#?\\s*on\\s+chat\\s+match\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_EV_GUI = Pattern.compile("^#?\\s*on\\s+gui\\s+open\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_LOOP_FOREVER = Pattern.compile("^loop\\s+forever$", FLAGS);
	private static final Pattern D_LOOP = Pattern.compile("^loop\\s+(\\d+)\\s+times$", FLAGS);
	private static final Pattern D_WAIT_RANDOM = Pattern.compile("^wait\\s+random\\s+(\\d+)\\s+to\\s+(\\d+)\\s+sec\\w*$", FLAGS);
	private static final Pattern D_WAIT_SEC = Pattern.compile("^wait\\s+(\\d+)\\s+sec\\w*$", FLAGS);
	private static final Pattern D_WAIT_TICK = Pattern.compile("^wait\\s+(\\d+)\\s+ticks?$", FLAGS);
	private static final Pattern D_WAIT_GUI = Pattern.compile("^wait\\s+for\\s+gui\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_IF_GUI = Pattern.compile("^if\\s+gui\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_IF_SLOT = Pattern.compile("^if\\s+slot\\s+(\\d+)\\s+has\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_IF_FREE = Pattern.compile("^if\\s+free\\s+space$", FLAGS);
	private static final Pattern D_SHIFT_CLICK = Pattern.compile("^shift\\s+click\\s+slot\\s+(\\d+)$", FLAGS);
	private static final Pattern D_CLICK = Pattern.compile("^click\\s+slot\\s+(\\d+)$", FLAGS);
	private static final Pattern D_RUN_CMD = Pattern.compile("^run\\s+command\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_SEND = Pattern.compile("^send\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_CHAT = Pattern.compile("^chat\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_SAY = Pattern.compile("^say\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_NOTIFY = Pattern.compile("^notify\\s+\"(.*)\"$", FLAGS);
	private static final Pattern D_PRESS = Pattern.compile("^(?:press\\s+)?(attack|use|jump|forward)\\s+for\\s+(\\d+)\\s+ticks?$", FLAGS);
	private static final Pattern D_SNEAK = Pattern.compile("^sneak\\s+(on|off)$", FLAGS);
	private static final Pattern D_HOTBAR = Pattern.compile("^hotbar\\s+([1-9])$", FLAGS);
	private static final Pattern D_LOOK = Pattern.compile("^look\\s+(-?\\d+)\\s+(-?\\d+)$", FLAGS);

	private static final class Cursor {
		int index;
	}

	/**
	 * Best-effort parse of a text script. Lines we cannot represent become
	 * {@code raw} blocks holding the original text, so compiling again gives
	 * back the identical line.
	 */
	public static List<Block> decompile(List<String> lines) {
		List<Block> out = new ArrayList<>();
		Cursor cursor = new Cursor();
		parseBlocks(lines, cursor, out, null);
		return out;
	}

	/** @return true when the {@code stop} terminator was consumed. */
	private static boolean parseBlocks(List<String> lines, Cursor cursor, List<Block> out, String stop) {
		while (cursor.index < lines.size()) {
			String original = lines.get(cursor.index);
			String trimmed = original.trim();
			String lower = trimmed.toLowerCase(Locale.ROOT);
			if (stop != null && lower.equals(stop)) {
				cursor.index++;
				return true;
			}
			cursor.index++;

			Matcher m;
			if ((m = D_EV_CHAT.matcher(trimmed)).matches()) {
				container(lines, cursor, out, original, "end on", one("event_chat", m.group(1)));
			} else if ((m = D_EV_GUI.matcher(trimmed)).matches()) {
				container(lines, cursor, out, original, "end on", one("event_gui_open", m.group(1)));
			} else if (D_JOIN.matcher(trimmed).matches()) {
				out.add(create("event_join"));
			} else if (D_LOOP_FOREVER.matcher(trimmed).matches()) {
				container(lines, cursor, out, original, "end loop", create("repeat_forever"));
			} else if ((m = D_LOOP.matcher(trimmed)).matches()) {
				container(lines, cursor, out, original, "end loop", one("repeat", m.group(1)));
			} else if ((m = D_IF_GUI.matcher(trimmed)).matches()) {
				container(lines, cursor, out, original, "end if", one("if_gui", m.group(1)));
			} else if ((m = D_IF_SLOT.matcher(trimmed)).matches()) {
				container(lines, cursor, out, original, "end if", two("if_slot_item", m.group(1), m.group(2)));
			} else if (D_IF_FREE.matcher(trimmed).matches()) {
				container(lines, cursor, out, original, "end if", create("if_free_space"));
			} else if ((m = D_WAIT_RANDOM.matcher(trimmed)).matches()) {
				out.add(two("wait_random", m.group(1), m.group(2)));
			} else if ((m = D_WAIT_SEC.matcher(trimmed)).matches()) {
				out.add(one("wait_sec", m.group(1)));
			} else if ((m = D_WAIT_TICK.matcher(trimmed)).matches()) {
				out.add(one("wait_ticks", m.group(1)));
			} else if ((m = D_WAIT_GUI.matcher(trimmed)).matches()) {
				out.add(one("wait_for_gui", m.group(1)));
			} else if (lower.equals("stop")) {
				out.add(create("stop"));
			} else if ((m = D_SHIFT_CLICK.matcher(trimmed)).matches()) {
				out.add(one("shift_click_slot", m.group(1)));
			} else if ((m = D_CLICK.matcher(trimmed)).matches()) {
				out.add(one("click_slot", m.group(1)));
			} else if (lower.equals("close gui")) {
				out.add(create("close_gui"));
			} else if (lower.equals("sort")) {
				out.add(create("sort_gui"));
			} else if ((m = D_RUN_CMD.matcher(trimmed)).matches()) {
				out.add(one("send_command", "/" + m.group(1).replaceFirst("^/", "")));
			} else if ((m = D_SEND.matcher(trimmed)).matches()) {
				out.add(one("send_message", m.group(1)));
			} else if ((m = D_CHAT.matcher(trimmed)).matches()) {
				out.add(one("send_message", m.group(1)));
			} else if ((m = D_SAY.matcher(trimmed)).matches()) {
				out.add(one("say_local", m.group(1)));
			} else if ((m = D_NOTIFY.matcher(trimmed)).matches()) {
				out.add(one("notify", m.group(1)));
			} else if ((m = D_PRESS.matcher(trimmed)).matches()) {
				out.add(two("press_key", m.group(1).toLowerCase(Locale.ROOT), m.group(2)));
			} else if ((m = D_SNEAK.matcher(trimmed)).matches()) {
				out.add(one("sneak", String.valueOf("on".equalsIgnoreCase(m.group(1)))));
			} else if ((m = D_HOTBAR.matcher(trimmed)).matches()) {
				out.add(one("hotbar", m.group(1)));
			} else if ((m = D_LOOK.matcher(trimmed)).matches()) {
				out.add(two("look", m.group(1), m.group(2)));
			} else if (lower.equals("drop")) {
				out.add(create("drop"));
			} else if (lower.equals("swap hands")) {
				out.add(create("swap_hands"));
			} else if (lower.equals("open inventory")) {
				out.add(create("open_inventory"));
			} else {
				out.add(raw(original));
			}
		}
		return false;
	}

	/**
	 * Parses a C-block body. If the terminator is missing the opener is demoted
	 * to a raw line and the body is spliced in, so nothing is invented on
	 * compile.
	 */
	private static void container(List<String> lines, Cursor cursor, List<Block> out,
								  String openerLine, String terminator, Block block) {
		List<Block> body = new ArrayList<>();
		boolean closed = parseBlocks(lines, cursor, body, terminator);
		if (closed) {
			block.children.addAll(body);
			out.add(block);
		} else {
			out.add(raw(openerLine));
			out.addAll(body);
		}
	}

	private static Block one(String type, String value) {
		Block block = create(type);
		block.setParam(0, value);
		return block;
	}

	private static Block two(String type, String a, String b) {
		Block block = create(type);
		block.setParam(0, a);
		block.setParam(1, b);
		return block;
	}

	// ------------------------------------------------------------------
	// warnings
	// ------------------------------------------------------------------

	/** Human-readable problems: unsupported commands and runtime nesting limits. */
	public static List<String> warnings(List<Block> blocks) {
		List<String> out = new ArrayList<>();
		scanWarnings(blocks, out, 0, 0);
		return out;
	}

	private static void scanWarnings(List<Block> blocks, List<String> out, int loopDepth, int ifDepth) {
		for (Block block : blocks) {
			BlockDef def = DEFS.get(block.type);
			if (def == null) continue;
			if (!def.engineSupported) {
				String message = "§e" + def.label.replaceAll("%\\d", "…") + " §7needs §f" + def.needsCommand;
				if (!out.contains(message)) out.add(message);
			}
			boolean isLoop = "repeat".equals(block.type) || "repeat_forever".equals(block.type);
			boolean isIf = def.container && !isLoop;
			int nextLoop = loopDepth + (isLoop ? 1 : 0);
			int nextIf = ifDepth + (isIf ? 1 : 0);
			if (isLoop && nextLoop > 1) {
				String message = "§cNested repeats: the engine only tracks one loop";
				if (!out.contains(message)) out.add(message);
			}
			if (isIf && nextIf > 1) {
				String message = "§cNested ifs: the engine jumps to the first §fend if";
				if (!out.contains(message)) out.add(message);
			}
			scanWarnings(block.children, out, nextLoop, nextIf);
			scanWarnings(block.elseChildren, out, nextLoop, nextIf);
		}
	}

	// ------------------------------------------------------------------
	// persistence (own JSON file - never touches ForgeCalcConfig)
	// ------------------------------------------------------------------

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-blockscripts.json");
	}

	public static Path backupFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-blockscripts.json.bak");
	}

	/** Set when the last load failed and the broken file was parked in the .bak. */
	public static String lastLoadError = "";

	public static List<Doc> loadAll() {
		lastLoadError = "";
		List<Doc> docs = new ArrayList<>();
		Path path = file();
		try {
			if (!Files.exists(path)) return docs;
			JsonElement root = JsonParser.parseString(Files.readString(path));
			JsonArray scripts = root.getAsJsonObject().getAsJsonArray("scripts");
			if (scripts == null) return docs;
			for (JsonElement element : scripts) {
				JsonObject object = element.getAsJsonObject();
				Doc doc = new Doc(object.has("name") ? object.get("name").getAsString() : "script");
				if (object.has("updated")) doc.updated = object.get("updated").getAsLong();
				readBlocks(object.getAsJsonArray("blocks"), doc.blocks);
				docs.add(doc);
			}
		} catch (Exception e) {
			// never lose the user's work: park the unreadable file next to it
			try {
				Files.copy(path, backupFile(), StandardCopyOption.REPLACE_EXISTING);
				lastLoadError = "§cUnreadable JSON - kept a copy as .bak";
			} catch (Exception ignored) {
				lastLoadError = "§cUnreadable JSON";
			}
			return new ArrayList<>();
		}
		return docs;
	}

	private static void readBlocks(JsonArray array, List<Block> out) {
		if (array == null) return;
		for (JsonElement element : array) {
			JsonObject object = element.getAsJsonObject();
			String type = object.has("type") ? object.get("type").getAsString() : "raw";
			Block block;
			if (DEFS.containsKey(type)) {
				block = new Block(type);
				JsonArray params = object.getAsJsonArray("params");
				if (params != null) {
					for (JsonElement param : params) block.params.add(param.getAsString());
				}
				BlockDef def = DEFS.get(type);
				while (block.params.size() < def.params.size()) {
					block.params.add(def.params.get(block.params.size()).defaultValue);
				}
			} else {
				// a block from a newer version: keep it visible instead of dropping it
				block = raw("# unknown block " + type);
			}
			readBlocks(object.getAsJsonArray("children"), block.children);
			readBlocks(object.getAsJsonArray("else"), block.elseChildren);
			out.add(block);
		}
	}

	public static void saveAll(List<Doc> docs) {
		try {
			Path path = file();
			if (Files.exists(path)) {
				// rolling backup of the last good state
				try {
					Files.copy(path, backupFile(), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
				}
			}
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray scripts = new JsonArray();
			for (Doc doc : docs) {
				JsonObject object = new JsonObject();
				object.addProperty("name", doc.name);
				object.addProperty("updated", doc.updated);
				object.add("blocks", writeBlocks(doc.blocks));
				scripts.add(object);
			}
			root.add("scripts", scripts);
			Files.createDirectories(path.getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root));
		} catch (Exception ignored) {
		}
	}

	private static JsonArray writeBlocks(List<Block> blocks) {
		JsonArray array = new JsonArray();
		for (Block block : blocks) {
			JsonObject object = new JsonObject();
			object.addProperty("type", block.type);
			JsonArray params = new JsonArray();
			for (String value : block.params) params.add(value);
			object.add("params", params);
			if (!block.children.isEmpty()) object.add("children", writeBlocks(block.children));
			if (!block.elseChildren.isEmpty()) object.add("else", writeBlocks(block.elseChildren));
			array.add(object);
		}
		return array;
	}

	// ------------------------------------------------------------------
	// bridges to the existing text engine
	// ------------------------------------------------------------------

	public static String safeName(String name) {
		String out = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9 _-]", "_").replace(' ', '_');
		return out.isEmpty() ? "block_script" : out;
	}

	/** Compiles and hands the script to the existing ScriptEngine. */
	public static boolean run(MinecraftClient client, Doc doc) {
		if (ForgeOreCalculatorClient.INSTANCE == null || client == null || client.player == null) return false;
		List<String> lines = compile(doc.blocks);
		if (lines.isEmpty()) return false;
		return ForgeOreCalculatorClient.INSTANCE.scriptEngine.start(safeName(doc.name) + ".txt", lines, client);
	}

	/** Writes the compiled text into the normal script folder so ScriptScreen sees it. */
	public static Path exportTxt(Doc doc) throws Exception {
		Path folder = ScriptEngine.scriptFolder();
		Files.createDirectories(folder);
		Path path = folder.resolve(safeName(doc.name) + ".txt");
		Files.write(path, compile(doc.blocks));
		return path;
	}

	/** Opens an existing .txt script as blocks. */
	public static Doc importTxt(Path path) throws Exception {
		Doc doc = new Doc(path.getFileName().toString().replace(".txt", ""));
		doc.blocks.addAll(decompile(Files.readAllLines(path)));
		return doc;
	}
}
