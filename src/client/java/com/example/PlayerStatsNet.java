package com.example;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pulls a player's public stats from afkable.com.
 *
 * <p>State lives in independent {@link Slot}s so compare mode can have two players in flight at
 * once without either request clobbering the other. Every fetch runs on its own daemon thread and
 * carries a generation stamp, so a slow response that lands after a newer request was started is
 * dropped instead of overwriting fresher data. The render thread only ever reads volatile fields -
 * it never blocks and never touches the network.
 */
public class PlayerStatsNet {

	public static final int IDLE = 0;
	public static final int LOADING = 1;
	public static final int DONE = 2;
	public static final int FAILED = 3;

	public static final int SLOT_A = 0;
	public static final int SLOT_B = 1;
	private static final int SLOT_COUNT = 2;

	private static final String ENDPOINT = "https://afkable.com/api/player/";

	/** One request's worth of state. Everything a screen reads is volatile and non-blocking. */
	public static final class Slot {
		private final int index;
		private final AtomicLong generation = new AtomicLong();

		private volatile int state = IDLE;
		private volatile String error = "";
		private volatile String query = "";
		private volatile JsonObject raw;
		private volatile StatsModels.PlayerProfile profile;
		private volatile long completedAt;

		private Slot(int index) {
			this.index = index;
		}

		public int index() {
			return index;
		}

		public int state() {
			return state;
		}

		public boolean loading() {
			return state == LOADING;
		}

		public boolean ready() {
			return state == DONE && profile != null;
		}

		public String error() {
			return error;
		}

		/** The username this slot last looked up. */
		public String query() {
			return query;
		}

		/** Parsed profile, or null unless {@link #ready()}. */
		public StatsModels.PlayerProfile profile() {
			return profile;
		}

		/** The untouched response body, kept so future screens can read fields we do not model. */
		public JsonObject raw() {
			return raw;
		}

		public long completedAt() {
			return completedAt;
		}

		public List<StatsModels.ServerProfile> servers() {
			StatsModels.PlayerProfile p = profile;
			return p == null ? new ArrayList<>() : p.servers();
		}

		public String playerName() {
			StatsModels.PlayerProfile p = profile;
			return p == null ? query : p.name();
		}

		public String playerUuid() {
			StatsModels.PlayerProfile p = profile;
			return p == null ? "" : p.uuid();
		}

		private void reset() {
			generation.incrementAndGet();
			state = IDLE;
			error = "";
			query = "";
			raw = null;
			profile = null;
			completedAt = 0;
		}
	}

	private static final Slot[] SLOTS = new Slot[SLOT_COUNT];

	static {
		for (int i = 0; i < SLOT_COUNT; i++) SLOTS[i] = new Slot(i);
	}

	private static volatile HttpClient http;

	private static HttpClient client() {
		HttpClient local = http;
		if (local == null) {
			synchronized (PlayerStatsNet.class) {
				local = http;
				if (local == null) {
					local = HttpClient.newBuilder()
							.connectTimeout(Duration.ofSeconds(8))
							.followRedirects(HttpClient.Redirect.NORMAL)
							.build();
					http = local;
				}
			}
		}
		return local;
	}

	/** Clamps any caller-supplied slot id into range so a bad index can never blow up a render. */
	public static Slot slot(int index) {
		if (index < 0 || index >= SLOT_COUNT) index = SLOT_A;
		return SLOTS[index];
	}

	// ---------------------------------------------------------------- fetching

	/**
	 * Starts a lookup in {@code slotIndex}. Returns the slot so the caller can poll it. Safe to
	 * call every frame - a request already in flight for the same name is not restarted.
	 */
	public static Slot fetch(String name, int slotIndex) {
		Slot slot = slot(slotIndex);
		if (name == null || name.isBlank()) return slot;
		String trimmed = name.trim();
		if (slot.state == LOADING && trimmed.equalsIgnoreCase(slot.query)) return slot;

		long generation = slot.generation.incrementAndGet();
		slot.query = trimmed;
		slot.error = "";
		slot.state = LOADING;

		Thread thread = new Thread(() -> run(slot, generation, trimmed), "afkable-stats-" + slot.index);
		thread.setDaemon(true);
		thread.start();
		return slot;
	}

	/** Re-runs the slot's last query. No-op when the slot has never been used. */
	public static Slot refresh(int slotIndex) {
		Slot slot = slot(slotIndex);
		String last = slot.query;
		if (last == null || last.isBlank()) return slot;
		slot.state = IDLE;
		return fetch(last, slotIndex);
	}

	private static void run(Slot slot, long generation, String name) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(ENDPOINT + encode(name)))
					.header("User-Agent", "AFKABLE-CLIENT")
					.header("Accept", "application/json")
					.timeout(Duration.ofSeconds(12))
					.GET().build();
			HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
			if (slot.generation.get() != generation) return; // superseded by a newer request

			int code = response.statusCode();
			if (code == 404) {
				finishFailed(slot, generation, "Player not found");
				return;
			}
			if (code < 200 || code >= 300) {
				finishFailed(slot, generation, "Server returned " + code);
				return;
			}

			JsonObject root;
			try {
				root = JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (Exception e) {
				finishFailed(slot, generation, "Bad response from server");
				return;
			}

			String status = root.has("status") && root.get("status").isJsonPrimitive()
					? root.get("status").getAsString() : "";
			if (!"ok".equalsIgnoreCase(status)) {
				finishFailed(slot, generation, "invalid".equalsIgnoreCase(status)
						? "No such player" : "Lookup failed (" + status + ")");
				return;
			}

			StatsModels.PlayerProfile profile;
			try {
				profile = StatsModels.parse(root);
			} catch (Throwable t) {
				finishFailed(slot, generation, "Could not read stats");
				return;
			}
			if (profile == null || profile.servers().isEmpty()) {
				finishFailed(slot, generation, "No server data");
				return;
			}

			if (slot.generation.get() != generation) return;
			slot.raw = root;
			slot.profile = profile;
			slot.completedAt = System.currentTimeMillis();
			slot.error = "";
			slot.state = DONE;
		} catch (java.net.http.HttpTimeoutException e) {
			finishFailed(slot, generation, "Timed out");
		} catch (java.net.ConnectException | java.net.UnknownHostException e) {
			finishFailed(slot, generation, "Cannot reach afkable.com");
		} catch (Throwable t) {
			String label = t.getClass().getSimpleName().replace("Exception", "");
			finishFailed(slot, generation, label.isEmpty() ? "Request failed" : label);
		}
	}

	private static void finishFailed(Slot slot, long generation, String message) {
		if (slot.generation.get() != generation) return;
		slot.profile = null;
		slot.raw = null;
		slot.error = message;
		slot.completedAt = System.currentTimeMillis();
		slot.state = FAILED;
	}

	private static String encode(String name) {
		StringBuilder out = new StringBuilder(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '_' || c == '-' || c == '.') {
				out.append(c);
			}
		}
		return out.toString();
	}

	// ---------------------------------------------------------------- per-slot accessors

	public static int state(int slotIndex) {
		return slot(slotIndex).state();
	}

	public static String error(int slotIndex) {
		return slot(slotIndex).error();
	}

	public static StatsModels.PlayerProfile profile(int slotIndex) {
		return slot(slotIndex).profile();
	}

	public static List<StatsModels.ServerProfile> servers(int slotIndex) {
		return slot(slotIndex).servers();
	}

	public static void reset(int slotIndex) {
		slot(slotIndex).reset();
	}

	public static void resetAll() {
		for (Slot slot : SLOTS) slot.reset();
	}

	// ---------------------------------------------------------------- legacy single-slot API
	// Kept so older call sites keep compiling; everything here just drives slot A.

	public static void fetch(String name) {
		fetch(name, SLOT_A);
	}

	public static int state() {
		return state(SLOT_A);
	}

	public static String error() {
		return error(SLOT_A);
	}

	public static String playerName() {
		return slot(SLOT_A).playerName();
	}

	public static String playerUuid() {
		return slot(SLOT_A).playerUuid();
	}

	public static List<StatsModels.ServerProfile> servers() {
		return servers(SLOT_A);
	}

	public static void reset() {
		reset(SLOT_A);
	}

	/** Friendly name for a server id, kept here for call sites that already used it. */
	public static String serverLabel(String id) {
		return StatsModels.serverLabel(id);
	}
}
