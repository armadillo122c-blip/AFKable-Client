package com.example;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads chat-game prompts and works out the answer.
 *
 * A round on this server looks like:
 * <pre>
 *   CHAT GAME | Trivia!
 *   What is 2 to the power of 5?
 *   First correct answer wins 2 CREDITS!
 *   ...
 *   CHAT GAME Winner! (5.7s)
 *   someone got it - 32!
 *   -&gt; They have been awarded 2 CREDITS!
 * </pre>
 * The header is one of {@code CHAT GAME}, {@code HEAD SCRATCHER} or {@code BRAIN BLAST}
 * (the legacy generic "trivia"/"unscramble" wording is still accepted). Puzzle lines may
 * carry a trailing per-round id such as {@code #7746}; the id is stripped before solving
 * but kept so two rounds sharing the same puzzle text are still told apart.
 *
 * Kinds handled:
 *   - math          "6 x 9 + 3" / "2 to the power of 5" / "9 squared"  -> evaluated with
 *                   real operator precedence (shunting-yard), not a two-operand regex
 *   - unscramble    "A word got shuffled - fix it: I I O S D N A B"    -> solved against
 *                   the word book (seeded with ~170 built-in server words)
 *   - command       "which command ..."  -> the /command without the slash
 *   - trivia        anything else        -> looked up in the learned answer book
 *
 * Everything it sees is remembered: when the server announces the winner and the
 * answer ("someone got it - OBSIDIAN!"), that prompt/answer pair is written to disk, so
 * the same question is answered instantly next time. With auto-send on it types the
 * answer for you.
 */
public class ChatGameHelper {

	// ---------- round framing ----------

	/** Named game headers. Matched case-insensitively anywhere in the line. */
	private static final String[] GAME_HEADERS = {"CHAT GAME", "HEAD SCRATCHER", "BRAIN BLAST"};

	/** Trailing per-round id, e.g. "... 6 x 9 + 3 #7746". */
	private static final Pattern ROUND_ID = Pattern.compile("\\s*#(\\d{2,10})\\s*[!.?]*\\s*$");

	/** Round-opening stake line: "First correct answer wins 2 CREDITS!" */
	private static final Pattern STAKE_LINE = Pattern.compile(
			"(?i)\\b(?:first|fastest|quickest)\\b[^.!?]*\\bwins?\\b"
					+ "|\\bwins?\\s+\\d+\\s*(?:credit|credits|token|tokens|coin|coins|gem|gems)\\b");
	/** Round-closing payout line: "-> They have been awarded 2 CREDITS!" */
	private static final Pattern PAYOUT_LINE = Pattern.compile(
			"(?i)\\b(?:have|has)\\s+been\\s+awarded\\b|\\bawarded\\s+\\d+\\s*[A-Za-z]");

	/** "esperronbo got it - OBSIDIAN!" */
	private static final Pattern GOT_IT = Pattern.compile(
			"(?i)\\b(?:got it|guessed it|nailed it|won it)\\b\\s*[-\\u2013\\u2014:>]+\\s*([^!?.]{1,40})");

	/**
	 * "X guessed the correct answer: banana" / "the answer was banana".
	 *
	 * <p>The bare phrase "correct answer" used to be enough, which meant the round-opening
	 * stake line "First correct answer wins 2 CREDITS!" was read as a reveal and taught the
	 * answer book that the answer was literally "wins 2 CREDITS". With auto-send on, the very
	 * next round would have typed that into public chat. A reveal now has to be introduced by
	 * a separator or a past-tense verb, and {@link #looksLikePrize} rejects anything that is
	 * really a payout.
	 */
	private static final Pattern REVEALED = Pattern.compile(
			"(?i)(?:guessed\\s+(?:the\\s+)?correct\\s+answer|correct\\s+answer\\s*[:\\-\\u2013]"
					+ "|the\\s+answer\\s+(?:was|is)|\\banswer\\s*:)"
					+ "\\s*[:\\-\\u2013]?\\s*([A-Za-z0-9 ']{1,32})");

	/** Words that mean "this is the prize", not "this is the answer". */
	private static final Pattern PRIZE_WORDS = Pattern.compile(
			"(?i)\\b(?:wins?|won|award(?:ed)?|receives?|earns?|credits?|tokens?|coins?|gems?|"
					+ "points?|prize|reward)\\b");

	private static boolean looksLikePrize(String candidate) {
		return candidate != null && PRIZE_WORDS.matcher(candidate).find();
	}

	// ---------- puzzle patterns ----------

	private static final Pattern SLASH_CMD = Pattern.compile("/([a-z][a-z0-9]{1,20})");
	private static final Pattern UNSCRAMBLE = Pattern.compile(
			"(?i)(?:unscramble|anagram|scrambled word|jumbled word)[^A-Za-z0-9]{0,12}([A-Za-z]{3,24})");
	private static final Pattern AFTER_COLON = Pattern.compile("[:\\-\\u2013]\\s*([A-Za-z]{3,24})\\s*$");
	private static final Pattern TYPE_THIS = Pattern.compile(
			"(?i)(?:type|say|write)\\s+[\"'«]?([A-Za-z0-9 ]{2,32})[\"'»]?");
	/** Three or more stand-alone letters in a row: "I I O S D N A B". */
	private static final Pattern SPACED_LETTERS = Pattern.compile(
			"(?<![A-Za-z])([A-Za-z](?:\\s+[A-Za-z]){2,})(?![A-Za-z])");
	private static final Pattern SCRAMBLE_WORDS = Pattern.compile(
			"(?i)\\b(?:unscramble|scrambled|shuffled|jumbled|anagram|mixed[- ]?up|rearrange|fix it)\\b");

	/** How long after the puzzle line a reveal is still credited to that round. */
	private static final long LEARN_WINDOW_MS = 180_000L;
	/** A round with no reveal goes stale after this. */
	private static final long ROUND_TTL_MS = 120_000L;

	// ---------- live state ----------

	private static String currentPrompt = "";
	private static String suggestedAnswer = "";
	private static String rawPrompt = "";
	private static String currentRoundId = "";
	private static String currentGame = "";
	private static String currentType = "";
	private static String roundStamp = "";
	private static long shownUntil = 0;
	private static long roundStartedAt = 0;
	private static boolean gameActive = false;
	private static boolean sentThisRound = false;

	/** Learned prompt -> answer pairs, persisted between sessions. */
	private static final Map<String, String> ANSWER_BOOK = new LinkedHashMap<>();
	/** Every word we know as a possible unscramble answer (seeds + learned). */
	private static final Set<String> WORD_BOOK = new LinkedHashSet<>();
	private static boolean loaded = false;

	public static String sendKeyName() {
		return "ENTER";
	}

	// ---------- built-in word book ----------

	/**
	 * Words that are plausible answers on this server, so the very first unscramble can be
	 * solved with no prior learning. Ores come from {@link OreData}; the rest are Minecraft
	 * mobs/blocks/items plus this server's own vocabulary (pets, ranks, commands, currency).
	 * Deliberately free of internal anagram pairs so a lookup is never ambiguous.
	 */
	private static final String[] SEED_WORDS = {
			// --- ores / OreData ---
			"coal", "copper", "iron", "zinc", "earth", "silver", "gold", "ruby", "sapphire",
			"diamond", "emerald", "obsidian", "uranium", "plutonium", "antimatter", "cosmic",
			"enderite", "stellar", "opal", "lithium", "titanium", "elemental", "atomic",
			"reactor", "quantum", "singularity", "neutron", "celestial", "mythril", "mithril",
			"cluster", "crystal", "void", "core", "ore", "shiny", "rainbow", "darkmatter",
			// --- blocks / items ---
			"stone", "cobblestone", "dirt", "grass", "sand", "gravel", "wood", "plank", "torch",
			"chest", "furnace", "anvil", "hopper", "piston", "redstone", "lever", "bucket",
			"sword", "pickaxe", "shovel", "arrow", "shield", "helmet", "chestplate", "leggings",
			"boots", "potion", "beacon", "nether", "portal", "bedrock", "ladder", "bookshelf",
			"netherite", "elytra", "trident", "totem", "spawner", "lava", "water", "glass",
			"brick", "wool", "leather", "string", "feather", "flint", "steel", "apple", "bread",
			"carrot", "potato", "wheat", "melon", "pumpkin", "cake", "cookie", "sugar", "honey",
			"bone", "pearl", "powder", "compass", "saddle", "banner", "lantern", "campfire",
			// --- mobs / pets ---
			"creeper", "zombie", "skeleton", "spider", "enderman", "blaze", "ghast", "slime",
			"witch", "villager", "pillager", "ravager", "warden", "piglin", "hoglin", "strider",
			"shulker", "phantom", "drowned", "husk", "vindicator", "evoker", "guardian",
			"wither", "dragon", "allay", "axolotl", "wolf", "chicken", "sheep", "horse", "llama",
			"panda", "turtle", "dolphin", "squid", "rabbit", "goat", "frog", "camel", "sniffer",
			"silverfish", "endermite", "magma", "golem", "parrot", "ocelot",
			// --- server / mod vocabulary ---
			"credits", "credit", "token", "tokens", "rank", "prestige", "rebirth", "vault",
			"backpack", "auction", "market", "gang", "guild", "pet", "pets", "boost", "booster",
			"crate", "keys", "quest", "daily", "weekly", "leaderboard", "balance", "money",
			"gems", "level", "multiplier", "enchant", "drill", "laser", "warp", "spawn", "home",
			"sethome", "tpaccept", "claim", "trash", "sort", "autosell", "dominion",
			"stock", "restock", "shop", "sell", "buy", "trade", "discord", "vote", "stats",
			"farm", "mine", "miner", "mining", "upgrade", "inventory", "hotbar", "server",
			"player", "client", "config", "settings", "keybind", "toggle", "scoreboard",
			"tablist", "chat", "message", "whisper", "friend", "party", "clan", "afkable"
	};

	private static final Set<String> SEEDS = new LinkedHashSet<>(Arrays.asList(SEED_WORDS));

	private static void seed() {
		WORD_BOOK.addAll(SEEDS);
	}

	// ---------- persistence ----------

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-chatgame.txt");
	}

	public static void load() {
		if (loaded) return;
		loaded = true;
		seed();
		try {
			Path path = file();
			if (!Files.exists(path)) return;
			for (String line : Files.readAllLines(path)) {
				if (line.isBlank() || line.startsWith("#")) continue;
				if (line.startsWith("word ")) {
					WORD_BOOK.add(line.substring(5).trim().toLowerCase(Locale.ROOT));
					continue;
				}
				int split = line.indexOf('=');
				if (split > 0) {
					ANSWER_BOOK.put(line.substring(0, split), line.substring(split + 1));
				}
			}
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to load chat game memory: " + e.getMessage());
		}
	}

	public static void save() {
		try {
			List<String> lines = new ArrayList<>();
			lines.add("# AFKABLE CLIENT - learned chat game answers");
			for (Map.Entry<String, String> entry : ANSWER_BOOK.entrySet()) {
				lines.add(entry.getKey() + "=" + entry.getValue());
			}
			for (String word : WORD_BOOK) {
				// built-in seeds live in the jar, no need to write them out every time
				if (SEEDS.contains(word)) continue;
				lines.add("word " + word);
			}
			Files.write(file(), lines);
		} catch (Exception e) {
			System.out.println("[ForgeCalc] Failed to save chat game memory: " + e.getMessage());
		}
	}

	public static int learnedCount() {
		return ANSWER_BOOK.size();
	}

	public static int wordCount() {
		return WORD_BOOK.size();
	}

	public static void forgetAll() {
		ANSWER_BOOK.clear();
		WORD_BOOK.clear();
		seed();
		save();
	}

	// ---------- chat handling ----------

	/** Strips colours and trims a prompt down to a stable lookup key. */
	private static String key(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
	}

	/** Small-caps -> ascii, section codes removed, whitespace collapsed. */
	private static String cleanLine(String raw) {
		// note: the no-op looking replace below normalises U+00A0 to a plain space
		String text = SortManager.normalizeText(raw)
				.replaceAll("(?i)§[0-9a-fk-orx]", "")
				.replace(' ', ' ');
		return text.replaceAll("\\s+", " ").trim();
	}

	/** The trailing "#7746" id, or "" when the line carries none. */
	public static String extractRoundId(String text) {
		Matcher m = ROUND_ID.matcher(text);
		return m.find() ? m.group(1) : "";
	}

	/** The same line with its trailing round id removed. */
	public static String stripRoundId(String text) {
		return ROUND_ID.matcher(text).replaceAll("").trim();
	}

	/** The canonical header found in this line, or null. */
	private static String matchHeader(String lower) {
		for (String header : GAME_HEADERS) {
			if (lower.contains(header.toLowerCase(Locale.ROOT))) return header;
		}
		return null;
	}

	private static boolean isRoundEnd(String lower) {
		return lower.contains("winner")
				|| lower.contains("guessed the correct")
				|| lower.contains("no one")
				|| lower.contains("nobody")
				|| lower.contains("time is up")
				|| lower.contains("times up")
				|| lower.contains("round over")
				|| lower.contains("expired")
				|| lower.contains("ran out");
	}

	public static void onChat(String rawMessage) {
		load();
		if (rawMessage == null) return;
		String text = cleanLine(rawMessage);
		if (text.isEmpty()) return;
		String lower = text.toLowerCase(Locale.ROOT);

		// a round that never resolved should not keep swallowing chat
		if (gameActive && roundStartedAt > 0 && System.currentTimeMillis() - roundStartedAt > ROUND_TTL_MS) {
			gameActive = false;
		}

		// 0) prize bookkeeping is classified FIRST. These lines contain the words "correct
		//    answer" and "awarded", so if the reveal check runs before them it mistakes the
		//    prize for the answer and poisons the answer book.
		boolean stakeLine = STAKE_LINE.matcher(text).find();
		boolean payoutLine = PAYOUT_LINE.matcher(text).find();

		// 1) the server revealed the answer - remember it against the prompt we were shown.
		//    Only while a round is in flight, so ordinary chat can never poison the book.
		boolean inRound = gameActive || roundStartedAt > 0 || !rawPrompt.isEmpty();
		if (inRound && !stakeLine && !payoutLine) {
			String revealed = extractReveal(text);
			if (revealed != null && !looksLikePrize(revealed)) {
				learnReveal(revealed);
				return;
			}
		}

		// 2) "BRAIN BLAST Winner! (5.7s)" - round is over, but the reveal line follows,
		//    so keep rawPrompt around for it and only drop what the HUD shows.
		if (isRoundEnd(lower)) {
			gameActive = false;
			clearVisible();
			return;
		}

		// 3) a named header starts a new round: "BRAIN BLAST | Mixed-up word!"
		String header = matchHeader(lower);
		if (header != null) {
			startRound(header, text);
			// some servers put the puzzle on the header line - harmless to try
			Solution inline = trySolve(stripRoundId(afterPipe(text)));
			if (inline != null) accept(stripRoundId(text), extractRoundId(text), inline);
			return;
		}

		// 4) "-> They have been awarded 2 CREDITS!" closes the round; never a puzzle,
		//    and it must not re-arm the parser or the next chat line looks like a prompt.
		if (payoutLine) {
			gameActive = false;
			clear();
			return;
		}

		// 5) the stake line just confirms a game is running; it is never the puzzle
		if (stakeLine) {
			gameActive = true;
			if (roundStartedAt == 0) roundStartedAt = System.currentTimeMillis();
			return;
		}

		boolean looksLikePrompt = gameActive
				|| lower.contains("trivia")
				|| lower.contains("unscramble")
				|| lower.contains("first to type");
		if (!looksLikePrompt) return;
		// pure decoration lines ("▬▬▬▬") are not puzzles
		if (text.replaceAll("[^A-Za-z0-9]", "").length() < 2) return;

		String id = extractRoundId(text);
		String puzzle = stripRoundId(text);

		// remember the question line even when we cannot solve it, so the reveal teaches us
		if (rawPrompt.isEmpty()) {
			rawPrompt = puzzle;
			currentRoundId = id;
			if (roundStartedAt == 0) roundStartedAt = System.currentTimeMillis();
		}

		Solution solution = trySolve(puzzle);
		if (solution == null) return;
		accept(puzzle, id, solution);
	}

	/** Text after the "|" separator on a header line, or the whole line. */
	private static String afterPipe(String text) {
		int bar = text.indexOf('|');
		return bar >= 0 ? text.substring(bar + 1).trim() : text;
	}

	private static void startRound(String header, String text) {
		clear();
		gameActive = true;
		currentGame = header;
		currentType = afterPipe(text).replaceAll("[!.]+$", "").trim();
		currentRoundId = extractRoundId(text);
		roundStartedAt = System.currentTimeMillis();
		roundStamp = "";
	}

	/** Publishes a solved puzzle, resetting the send guard when the round changed. */
	private static void accept(String puzzle, String id, Solution solution) {
		rawPrompt = puzzle;
		currentRoundId = id;
		if (roundStartedAt == 0) roundStartedAt = System.currentTimeMillis();
		String stamp = key(puzzle) + "#" + id;
		if (!stamp.equals(roundStamp)) {
			roundStamp = stamp;
			sentThisRound = false;
		}
		set(solution.prompt, solution.answer);
	}

	private static String extractReveal(String text) {
		Matcher gotIt = GOT_IT.matcher(text);
		if (gotIt.find()) return gotIt.group(1);
		Matcher revealed = REVEALED.matcher(text);
		if (revealed.find()) return revealed.group(1);
		return null;
	}

	private static void learnReveal(String rawAnswer) {
		String answer = rawAnswer.trim().replaceAll("[!?.,]+$", "").trim();
		// last line of defence: never store a prize as if it were the answer
		if (looksLikePrize(answer)) return;
		boolean fresh = roundStartedAt > 0 && System.currentTimeMillis() - roundStartedAt < LEARN_WINDOW_MS;
		if (!answer.isEmpty() && answer.length() <= 40) {
			boolean dirty = false;
			if (fresh && !rawPrompt.isEmpty()) {
				ANSWER_BOOK.put(key(rawPrompt), answer);
				dirty = true;
			}
			if (answer.matches("[A-Za-z]{3,24}")) {
				dirty |= WORD_BOOK.add(answer.toLowerCase(Locale.ROOT));
			}
			if (dirty) save();
		}
		gameActive = false;
		clear();
	}

	// ---------- solving ----------

	/** A worked-out puzzle: what to show on the HUD, and what to type. */
	private static final class Solution {
		final String prompt;
		final String answer;

		Solution(String prompt, String answer) {
			this.prompt = prompt;
			this.answer = answer;
		}
	}

	/** Works out a puzzle line that already had its round id stripped. Null if unsolved. */
	private static Solution trySolve(String puzzle) {
		if (puzzle == null || puzzle.isEmpty()) return null;
		String lower = puzzle.toLowerCase(Locale.ROOT);

		// 1) a prompt we have already been taught
		String remembered = ANSWER_BOOK.get(key(puzzle));
		if (remembered != null && !remembered.isEmpty()) {
			return new Solution(shorten(puzzle), remembered);
		}

		// 2) "type this exactly" style
		Matcher typeThis = TYPE_THIS.matcher(puzzle);
		if (typeThis.find() && (lower.contains("type") || lower.contains("say"))
				&& !lower.contains("command")) {
			String target = typeThis.group(1).trim();
			if (target.length() >= 2 && !target.equalsIgnoreCase("the")) {
				return new Solution(shorten(puzzle), target);
			}
		}

		// 3) unscramble - "A word got shuffled - fix it: I I O S D N A B"
		String scrambled = extractScramble(puzzle);
		if (scrambled != null) {
			String solved = unscramble(scrambled);
			return new Solution("Unscramble: " + scrambled.toUpperCase(Locale.ROOT),
					solved != null ? solved.toUpperCase(Locale.ROOT) : "§7unknown word");
		}

		// 4) maths, with real precedence
		String[] math = evalMath(puzzle);
		if (math != null) {
			return new Solution(math[0], math[1]);
		}

		// 5) "which command ..." style
		Matcher cmd = SLASH_CMD.matcher(puzzle);
		if (cmd.find() && (lower.contains("command") || lower.contains("which") || lower.contains("type"))) {
			return new Solution(shorten(puzzle), cmd.group(1).toLowerCase(Locale.ROOT));
		}
		return null;
	}

	/** Pulls the scrambled letters out of a puzzle line, or null when it is not one. */
	public static String extractScramble(String puzzle) {
		String text = stripRoundId(puzzle);
		boolean hint = SCRAMBLE_WORDS.matcher(text).find()
				|| currentType.toLowerCase(Locale.ROOT).contains("word")
				|| currentType.toLowerCase(Locale.ROOT).contains("scramble")
				|| currentType.toLowerCase(Locale.ROOT).contains("anagram");

		// "I I O S D N A B" -> "IIOSDNAB"
		Matcher spaced = SPACED_LETTERS.matcher(text);
		String best = null;
		while (spaced.find()) {
			String joined = spaced.group(1).replaceAll("\\s+", "");
			if (best == null || joined.length() > best.length()) best = joined;
		}
		if (best != null && best.length() >= (hint ? 3 : 4)) {
			return best.toLowerCase(Locale.ROOT);
		}
		if (!hint) return null;

		Matcher un = UNSCRAMBLE.matcher(text);
		if (un.find()) return un.group(1).toLowerCase(Locale.ROOT);

		Matcher tail = AFTER_COLON.matcher(text);
		if (tail.find()) return tail.group(1).toLowerCase(Locale.ROOT);
		return null;
	}

	/** Finds a known word whose letters match the scrambled input. Null when unknown. */
	public static String unscramble(String scrambled) {
		load(); // the word book is populated lazily; a direct caller must not see it empty
		if (scrambled == null || scrambled.isEmpty()) return null;
		String needle = scrambled.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		if (needle.isEmpty()) return null;
		char[] target = needle.toCharArray();
		Arrays.sort(target);
		String targetKey = new String(target);
		for (String word : WORD_BOOK) {
			if (word.length() != needle.length()) continue;
			char[] chars = word.toCharArray();
			Arrays.sort(chars);
			if (new String(chars).equals(targetKey)) return word;
		}
		return null;
	}

	// ---------- maths ----------

	private static final Map<String, Character> WORD_OPS = new LinkedHashMap<>();
	private static final Map<String, Integer> POSTFIX_POW = new LinkedHashMap<>();
	private static final Set<String> GLUE = new LinkedHashSet<>(Arrays.asList("to", "the", "of", "by"));

	static {
		WORD_OPS.put("plus", '+');
		WORD_OPS.put("add", '+');
		WORD_OPS.put("and", '+');
		WORD_OPS.put("minus", '-');
		WORD_OPS.put("subtract", '-');
		WORD_OPS.put("less", '-');
		WORD_OPS.put("times", '*');
		WORD_OPS.put("multiplied", '*');
		WORD_OPS.put("multiply", '*');
		WORD_OPS.put("x", '*');
		WORD_OPS.put("divided", '/');
		WORD_OPS.put("divide", '/');
		WORD_OPS.put("over", '/');
		WORD_OPS.put("power", '^');
		POSTFIX_POW.put("squared", 2);
		POSTFIX_POW.put("cubed", 3);
	}

	/**
	 * Evaluates the arithmetic in a line. Returns {pretty expression, answer} or null.
	 * Numbers, operators and glue words form "runs"; any other word breaks the run, so
	 * "First correct answer wins 2 CREDITS!" never evaluates to anything.
	 */
	public static String[] evalMath(String text) {
		if (text == null) return null;
		String lower = stripRoundId(text).toLowerCase(Locale.ROOT);
		List<Object> best = null;
		Double bestValue = null;
		for (List<Object> run : mathRuns(lower)) {
			if (!hasOperator(run)) continue;
			Double value = eval(run);
			if (value == null) continue;
			if (best == null || run.size() > best.size()) {
				best = run;
				bestValue = value;
			}
		}
		if (best == null) return null;
		return new String[]{pretty(best), fmt(bestValue)};
	}

	/** Convenience wrapper: just the answer, or null. */
	public static String solveMath(String text) {
		String[] result = evalMath(text);
		return result == null ? null : result[1];
	}

	/** Splits a line into runs of number/operator tokens. Doubles are values, Characters ops. */
	private static List<List<Object>> mathRuns(String lower) {
		List<List<Object>> runs = new ArrayList<>();
		List<Object> cur = new ArrayList<>();
		int i = 0;
		int n = lower.length();
		while (i < n) {
			char c = lower.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (Character.isDigit(c)) {
				StringBuilder num = new StringBuilder();
				int j = i;
				while (j < n) {
					char d = lower.charAt(j);
					if (Character.isDigit(d)) {
						num.append(d);
						j++;
					} else if (d == ',' && j + 1 < n && Character.isDigit(lower.charAt(j + 1))) {
						j++;
					} else if (d == '.' && j + 1 < n && Character.isDigit(lower.charAt(j + 1))) {
						num.append('.');
						j++;
					} else {
						break;
					}
				}
				i = j;
				try {
					cur.add(Double.valueOf(num.toString()));
				} catch (NumberFormatException e) {
					runs.add(cur);
					cur = new ArrayList<>();
				}
				continue;
			}
			if (Character.isLetter(c)) {
				int j = i;
				while (j < n && Character.isLetter(lower.charAt(j))) j++;
				String word = lower.substring(i, j);
				i = j;
				Character op = WORD_OPS.get(word);
				if (op != null) {
					cur.add(op);
					continue;
				}
				Integer power = POSTFIX_POW.get(word);
				if (power != null) {
					cur.add(Character.valueOf('^'));
					cur.add(Double.valueOf(power));
					continue;
				}
				if (GLUE.contains(word)) continue;
				runs.add(cur);
				cur = new ArrayList<>();
				continue;
			}
			switch (c) {
				case '+':
				case '-':
				case '*':
				case '/':
				case '^':
				case '(':
				case ')':
					cur.add(Character.valueOf(c));
					break;
				case '×':
					cur.add(Character.valueOf('*'));
					break;
				case '÷':
					cur.add(Character.valueOf('/'));
					break;
				case '−':
					cur.add(Character.valueOf('-'));
					break;
				default:
					runs.add(cur);
					cur = new ArrayList<>();
					break;
			}
			i++;
		}
		runs.add(cur);
		return runs;
	}

	private static boolean hasOperator(List<Object> run) {
		for (Object token : run) {
			if (token instanceof Character) {
				char c = (Character) token;
				if (c == '+' || c == '-' || c == '*' || c == '/' || c == '^') return true;
			}
		}
		return false;
	}

	private static int prec(char op) {
		switch (op) {
			case '^':
				return 3;
			case '*':
			case '/':
				return 2;
			case '+':
			case '-':
				return 1;
			default:
				return 0;
		}
	}

	/** Shunting-yard evaluation: correct precedence, right-associative '^', parentheses. */
	private static Double eval(List<Object> tokens) {
		Deque<Double> values = new ArrayDeque<>();
		Deque<Character> ops = new ArrayDeque<>();
		boolean expectValue = true;
		int sign = 1;
		for (Object token : tokens) {
			if (token instanceof Double) {
				if (!expectValue) return null;
				values.push(sign * (Double) token);
				sign = 1;
				expectValue = false;
				continue;
			}
			char c = (Character) token;
			if (c == '(') {
				if (!expectValue) return null;
				ops.push(Character.valueOf(c));
				continue;
			}
			if (c == ')') {
				if (expectValue) return null;
				while (!ops.isEmpty() && ops.peek() != '(') {
					if (!apply(values, ops.pop())) return null;
				}
				if (ops.isEmpty()) return null;
				ops.pop();
				expectValue = false;
				continue;
			}
			if (expectValue) {
				// leading sign, e.g. "-5 + 3"
				if (c == '-') {
					sign = -sign;
					continue;
				}
				if (c == '+') continue;
				return null;
			}
			while (!ops.isEmpty() && ops.peek() != '('
					&& (prec(ops.peek()) > prec(c) || (prec(ops.peek()) == prec(c) && c != '^'))) {
				if (!apply(values, ops.pop())) return null;
			}
			ops.push(Character.valueOf(c));
			expectValue = true;
		}
		if (expectValue) return null;
		while (!ops.isEmpty()) {
			char op = ops.pop();
			if (op == '(') return null;
			if (!apply(values, op)) return null;
		}
		if (values.size() != 1) return null;
		return values.pop();
	}

	private static boolean apply(Deque<Double> values, char op) {
		if (values.size() < 2) return false;
		double b = values.pop();
		double a = values.pop();
		double r;
		switch (op) {
			case '+':
				r = a + b;
				break;
			case '-':
				r = a - b;
				break;
			case '*':
				r = a * b;
				break;
			case '/':
				if (Math.abs(b) < 1e-12) return false;
				r = a / b;
				break;
			case '^':
				if (Math.abs(b) > 64) return false;
				r = Math.pow(a, b);
				break;
			default:
				return false;
		}
		if (Double.isNaN(r) || Double.isInfinite(r)) return false;
		values.push(r);
		return true;
	}

	private static String pretty(List<Object> run) {
		StringBuilder sb = new StringBuilder();
		for (Object token : run) {
			if (sb.length() > 0) sb.append(' ');
			if (token instanceof Double) sb.append(fmt((Double) token));
			else sb.append(((Character) token).charValue());
		}
		return sb.toString();
	}

	private static String fmt(double value) {
		if (Math.abs(value - Math.rint(value)) < 1e-9 && Math.abs(value) < 1e15) {
			return String.valueOf((long) Math.rint(value));
		}
		String s = String.format(Locale.ROOT, "%.4f", value);
		if (s.contains(".")) {
			s = s.replaceAll("0+$", "");
			s = s.replaceAll("\\.$", "");
		}
		return s;
	}

	// ---------- output ----------

	private static void set(String prompt, String answer) {
		currentPrompt = prompt;
		suggestedAnswer = answer;
		shownUntil = System.currentTimeMillis() + 30000;
	}

	/**
	 * Types the answer in chat when auto-send is on. Called once per tick from the
	 * client; guarded so each round only ever sends a single message.
	 */
	public static void tickAutoSend(MinecraftClient client) {
		if (!ForgeCalcConfig.chatGameAutoSend || sentThisRound) return;
		if (!hasPrompt() || client.player == null) return;
		String answer = suggestedAnswer;
		if (answer.isEmpty() || answer.startsWith("§")) return;
		sentThisRound = true;
		try {
			client.player.networkHandler.sendChatMessage(answer);
		} catch (Throwable ignored) {
		}
	}

	/** Sends the current answer right now, whatever the auto-send setting says. */
	public static boolean sendNow(MinecraftClient client) {
		if (!hasPrompt() || client.player == null) return false;
		String answer = suggestedAnswer;
		if (answer.isEmpty() || answer.startsWith("§")) return false;
		sentThisRound = true;
		try {
			client.player.networkHandler.sendChatMessage(answer);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static String shorten(String text) {
		String clean = text
				.replaceAll("(?i)(chat game|head scratcher|brain blast)\\s*[|:\\-]*\\s*", "")
				.trim();
		return clean.length() > 30 ? clean.substring(0, 29) + "…" : clean;
	}

	/** Drops the HUD panel but keeps the round context a reveal still needs. */
	private static void clearVisible() {
		currentPrompt = "";
		suggestedAnswer = "";
		shownUntil = 0;
	}

	public static void clear() {
		currentPrompt = "";
		suggestedAnswer = "";
		rawPrompt = "";
		currentRoundId = "";
		currentGame = "";
		currentType = "";
		roundStamp = "";
		shownUntil = 0;
		roundStartedAt = 0;
		sentThisRound = false;
	}

	public static boolean hasPrompt() {
		return !currentPrompt.isEmpty() && System.currentTimeMillis() < shownUntil;
	}

	public static String prompt() {
		return currentPrompt;
	}

	public static String answer() {
		return suggestedAnswer;
	}

	/** The "#7746" id of the round on screen, or "" when the round carried none. */
	public static String roundId() {
		return currentRoundId;
	}

	/** "CHAT GAME" / "HEAD SCRATCHER" / "BRAIN BLAST", or "" outside a named round. */
	public static String gameName() {
		return currentGame;
	}

	/** The bit after the pipe, e.g. "Mixed-up word", or "". */
	public static String gameType() {
		return currentType;
	}

	// ---------- self test ----------

	/**
	 * Hand-runnable check of the parser. Returns one line per case, prefixed OK or FAIL.
	 * Deliberately main-free: call it from a dev command or a breakpoint.
	 */
	public static String selfTest() {
		load();
		StringBuilder sb = new StringBuilder();
		check(sb, "power", "32", solveMath("What is 2 to the power of 5?"));
		check(sb, "precedence", "57", solveMath("Work it out: 6 x 9 + 3 #7746"));
		check(sb, "legacy add", "90", solveMath("25 + 65"));
		check(sb, "squared", "81", solveMath("9 squared"));
		check(sb, "cubed sum", "28", solveMath("3 cubed plus 1"));
		check(sb, "div", "5", solveMath("10 divided by 2"));
		check(sb, "parens", "20", solveMath("(2 + 3) x 4"));
		check(sb, "no math", null, solveMath("First correct answer wins 2 CREDITS!"));
		check(sb, "round id", "7746", extractRoundId("Work it out: 6 x 9 + 3 #7746"));
		check(sb, "strip id", "Work it out: 6 x 9 + 3", stripRoundId("Work it out: 6 x 9 + 3 #7746"));
		check(sb, "scramble", "iiosdnab",
				extractScramble("A word got shuffled - fix it: I I O S D N A B #4549"));
		check(sb, "anagram", "obsidian", unscramble("iiosdnab"));
		check(sb, "anagram2", "creeper", unscramble("rceeepr"));
		return sb.toString();
	}

	private static void check(StringBuilder sb, String name, String expected, String actual) {
		boolean ok = expected == null ? actual == null : expected.equals(actual);
		sb.append(ok ? "OK   " : "FAIL ").append(name)
				.append(" -> ").append(actual).append(ok ? "" : " (expected " + expected + ")")
				.append('\n');
	}
}
