package com.example;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous player / pet / farm-mob head texture cache.
 *
 * <p>The stats API hands us skin URLs ({@code http://textures.minecraft.net/texture/<hash>})
 * for every pet and farm mob. This class turns those into GUI-drawable {@link Identifier}s
 * without ever letting the render thread touch IO:
 *
 * <ol>
 *   <li>{@link #headFor(String, String)} is a pure lookup. It returns {@code null} on a miss
 *       and quietly schedules the download; callers draw a placeholder that frame.</li>
 *   <li>{@link #tick()} drains the pending queue through a token bucket so we never hammer
 *       the texture host, and hands jobs to a small daemon pool.</li>
 *   <li>Workers decode the skin, cut the 8x8 head at (8,8), blend the 8x8 hat at (40,8) over
 *       it, and cache the composed head on disk.</li>
 *   <li>Registration with the {@link TextureManager} is marshalled back onto the render
 *       thread via {@code MinecraftClient.execute} - doing it off-thread is a hard crash.</li>
 * </ol>
 *
 * <p>Everything is failure-tolerant: a 404, a timeout, an HTML error page, a truncated PNG or
 * a skin too small to contain a head all land in a negative cache with exponential backoff,
 * so a dead URL costs one request every few minutes at worst instead of one per frame.
 */
public final class HeadTextures {

	private HeadTextures() {
	}

	// ---------------------------------------------------------------- tuning

	private static final String NAMESPACE = "forgecalc";
	private static final String USER_AGENT = "AFKABLE-CLIENT (Minecraft mod; +https://afkable.com)";

	/** Hard cap on live GL textures. Oldest-used entries are destroyed past this. */
	private static final int MAX_TEXTURES = 256;
	/** Cap on queued-but-not-started downloads, so a huge screen cannot balloon memory. */
	private static final int MAX_PENDING = 512;
	private static final int POOL_THREADS = 3;

	/** Token bucket: sustained requests per second, and the burst we allow on a fresh screen. */
	private static final double RATE_PER_SECOND = 5.0;
	private static final double BURST = 8.0;

	private static final long CONNECT_TIMEOUT_SEC = 6;
	private static final long READ_TIMEOUT_SEC = 8;
	private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

	/** Negative cache backoff: 30s, 60s, 120s ... capped at 30 minutes. */
	private static final long BACKOFF_BASE_MS = 30_000L;
	private static final long BACKOFF_MAX_MS = 30L * 60_000L;
	private static final int BACKOFF_MAX_STEPS = 7;

	/** Player skins change; texture-hash URLs never do. Re-pull a player head once a day. */
	private static final long PLAYER_TTL_MS = 24L * 60L * 60L * 1000L;
	private static final long SWEEP_INTERVAL_MS = 30_000L;

	/**
	 * Skin URLs arrive inside untrusted network payloads, so we only ever connect to hosts we
	 * actually expect to serve skins. {@link #allowHost(String)} widens this at runtime.
	 */
	private static final Set<String> ALLOWED_HOSTS = ConcurrentHashMap.newKeySet();

	static {
		ALLOWED_HOSTS.add("textures.minecraft.net");
		ALLOWED_HOSTS.add("sessionserver.mojang.com");
		ALLOWED_HOSTS.add("minotar.net");
		ALLOWED_HOSTS.add("mc-heads.net");
		ALLOWED_HOSTS.add("crafatar.com");
		ALLOWED_HOSTS.add("api.mineatar.io");
		ALLOWED_HOSTS.add("afkable.com");
	}

	// ----------------------------------------------------------------- state

	/** A live, registered head texture. */
	private static final class Entry {
		final Identifier id;
		final int width;
		final int height;
		final boolean player;
		final long created;

		Entry(Identifier id, int width, int height, boolean player) {
			this.id = id;
			this.width = width;
			this.height = height;
			this.player = player;
			this.created = System.currentTimeMillis();
		}
	}

	private record Job(String cacheKey, String url, boolean player) {
	}

	/** Access-ordered so the eldest iteration entry is genuinely the least recently used. */
	private static final LinkedHashMap<String, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);

	/** Held from the moment a key is queued until it is either published or marked failed. */
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	/** cacheKey -> {retryNotBefore, consecutiveFailures}. */
	private static final Map<String, long[]> FAILED = new ConcurrentHashMap<>();

	private static final ArrayDeque<Job> PENDING = new ArrayDeque<>();

	private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

	private static final ExecutorService POOL = Executors.newFixedThreadPool(POOL_THREADS, run -> {
		Thread thread = new Thread(run, "afkable-heads-" + THREAD_SEQ.incrementAndGet());
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private static volatile HttpClient http;
	private static double tokens = BURST;
	private static long lastRefill;
	private static long lastSweep;

	// ------------------------------------------------------------ public API

	/**
	 * Head texture for an arbitrary keyed entity (pet, farm mob, ...).
	 *
	 * @param key        stable name for the entry; used only as a fallback when {@code textureUrl}
	 *                   is missing but the key itself is a bare 64-char skin hash
	 * @param textureUrl the API's {@code texture} field, e.g. {@code http://textures.minecraft.net/texture/<hash>}
	 * @return the registered texture, or {@code null} while it is loading / permanently unavailable
	 */
	public static Identifier headFor(String key, String textureUrl) {
		Entry entry = lookup(key, textureUrl);
		return entry == null ? null : entry.id;
	}

	/**
	 * Head texture for a Minecraft username, pulled from a raw-skin endpoint.
	 *
	 * @return the registered texture, or {@code null} while it is loading / unavailable
	 */
	public static Identifier playerHead(String username) {
		Entry entry = lookupPlayer(username);
		return entry == null ? null : entry.id;
	}

	/**
	 * Draws a head scaled into {@code size x size}.
	 *
	 * @return {@code false} when the texture is not ready, so the caller can draw a placeholder
	 */
	public static boolean drawHead(DrawContext ctx, String key, String url, int x, int y, int size) {
		if (ctx == null || size <= 0) return false;
		return draw(ctx, lookup(key, url), x, y, size);
	}

	/** Same as {@link #drawHead} but for a username. Returns {@code false} until ready. */
	public static boolean drawPlayerHead(DrawContext ctx, String username, int x, int y, int size) {
		if (ctx == null || size <= 0) return false;
		return draw(ctx, lookupPlayer(username), x, y, size);
	}

	/** True when the head is already resident, without scheduling a download. */
	public static boolean isReady(String key, String url) {
		String normalized = normalizeUrl(key, url);
		if (normalized == null) return false;
		synchronized (CACHE) {
			return CACHE.containsKey(cacheKeyFor(normalized));
		}
	}

	/** Must be called once per client tick. Drains the download queue and sweeps stale entries. */
	public static void tick() {
		try {
			long now = System.currentTimeMillis();
			if (lastRefill == 0L) lastRefill = now;
			tokens = Math.min(BURST, tokens + (now - lastRefill) / 1000.0 * RATE_PER_SECOND);
			lastRefill = now;

			while (tokens >= 1.0) {
				Job job;
				synchronized (PENDING) {
					job = PENDING.poll();
				}
				if (job == null) break;
				tokens -= 1.0;
				submit(job);
			}

			if (now - lastSweep >= SWEEP_INTERVAL_MS) {
				lastSweep = now;
				sweep(now);
			}
		} catch (Throwable ignored) {
			// A head cache must never be able to break the client tick loop.
		}
	}

	/**
	 * Drops every cached head: unregisters the GL textures, forgets failures, empties the queue
	 * and deletes the on-disk PNGs. Safe from any thread; texture destruction is marshalled.
	 */
	public static void clearCache() {
		synchronized (PENDING) {
			PENDING.clear();
		}
		IN_FLIGHT.clear();
		FAILED.clear();

		List<Identifier> doomed = new ArrayList<>();
		synchronized (CACHE) {
			for (Entry entry : CACHE.values()) doomed.add(entry.id);
			CACHE.clear();
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && !doomed.isEmpty()) {
			client.execute(() -> {
				TextureManager manager = textureManager();
				if (manager == null) return;
				for (Identifier id : doomed) {
					try {
						manager.destroyTexture(id);
					} catch (Throwable ignored) {
					}
				}
			});
		}

		try {
			POOL.execute(HeadTextures::wipeDisk);
		} catch (Throwable ignored) {
		}
	}

	/** Adds a host to the skin-download allowlist (exact hostname, lowercase). */
	public static void allowHost(String host) {
		if (host != null && !host.isBlank()) ALLOWED_HOSTS.add(host.trim().toLowerCase(Locale.ROOT));
	}

	// -------------------------------------------------------------- lookup

	private static boolean draw(DrawContext ctx, Entry entry, int x, int y, int size) {
		if (entry == null) return false;
		try {
			ctx.drawTexture(RenderPipelines.GUI_TEXTURED, entry.id, x, y, 0.0F, 0.0F,
					size, size, entry.width, entry.height, entry.width, entry.height);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static Entry lookup(String key, String textureUrl) {
		String url = normalizeUrl(key, textureUrl);
		if (url == null) return null;
		return resolve(cacheKeyFor(url), url, false);
	}

	private static Entry lookupPlayer(String username) {
		String name = sanitizeName(username);
		if (name == null) return null;
		return resolve("player_" + name, "https://minotar.net/skin/" + name + ".png", true);
	}

	private static Entry resolve(String cacheKey, String url, boolean player) {
		synchronized (CACHE) {
			Entry entry = CACHE.get(cacheKey);
			if (entry != null) return entry;
		}
		long[] failure = FAILED.get(cacheKey);
		if (failure != null && System.currentTimeMillis() < failure[0]) return null;
		enqueue(new Job(cacheKey, url, player));
		return null;
	}

	/** Per-key guard: a key stays in IN_FLIGHT from queue until publish or failure. */
	private static void enqueue(Job job) {
		if (!IN_FLIGHT.add(job.cacheKey())) return;
		boolean queued;
		synchronized (PENDING) {
			queued = PENDING.size() < MAX_PENDING;
			if (queued) PENDING.add(job);
		}
		if (!queued) IN_FLIGHT.remove(job.cacheKey());
	}

	private static void submit(Job job) {
		try {
			POOL.execute(() -> {
				try {
					run(job);
				} catch (Throwable t) {
					fail(job.cacheKey());
				}
			});
		} catch (Throwable t) {
			IN_FLIGHT.remove(job.cacheKey());
		}
	}

	// -------------------------------------------------------------- worker

	private static void run(Job job) {
		NativeImage head = readDisk(job);
		if (head == null) {
			byte[] png = fetch(job.url());
			if (png == null) {
				fail(job.cacheKey());
				return;
			}
			head = compose(png);
			if (head == null) {
				fail(job.cacheKey());
				return;
			}
			writeDisk(job.cacheKey(), head);
		}
		publish(job, head);
	}

	private static byte[] fetch(String url) {
		try {
			URI uri = new URI(url);
			if (!isAllowed(uri)) return null;
			HttpRequest request = HttpRequest.newBuilder(uri)
					.header("User-Agent", USER_AGENT)
					.header("Accept", "image/png,image/*;q=0.8")
					.timeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
					.GET()
					.build();
			HttpResponse<byte[]> response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) return null;
			byte[] body = response.body();
			if (body == null || body.length < 64 || body.length > MAX_BODY_BYTES) return null;
			return isPng(body) ? body : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Cuts the head out of a skin PNG and blends the hat overlay over it.
	 * Head = 8x8 at (8,8), hat = 8x8 at (40,8), both scaled with the skin's own resolution.
	 *
	 * @return an owned 8x8 (or HD multiple) image, or {@code null} if the PNG is not a usable skin
	 */
	private static NativeImage compose(byte[] png) {
		NativeImage skin = null;
		NativeImage out = null;
		try {
			skin = NativeImage.read(new ByteArrayInputStream(png));
			int width = skin.getWidth();
			int height = skin.getHeight();
			// Valid skins are 64x64 or legacy 64x32, optionally at an integer HD multiple.
			if (width < 64 || width % 64 != 0) return null;
			if (height != width && height * 2 != width) return null;

			int unit = width / 64;
			int scale = Math.min(unit, 4);
			int span = 8 * scale;
			int step = unit / scale;

			int headX = 8 * unit;
			int headY = 8 * unit;
			int hatX = 40 * unit;
			int hatY = 8 * unit;
			if (headX + 8 * unit > width || headY + 8 * unit > height) return null;
			boolean hasHat = hatX + 8 * unit <= width && hatY + 8 * unit <= height;

			out = new NativeImage(span, span, false);
			for (int y = 0; y < span; y++) {
				for (int x = 0; x < span; x++) {
					int sx = x * step;
					int sy = y * step;
					int base = skin.getColorArgb(headX + sx, headY + sy) | 0xFF000000;
					int argb = base;
					if (hasHat) {
						int hat = skin.getColorArgb(hatX + sx, hatY + sy);
						int alpha = (hat >>> 24) & 0xFF;
						if (alpha == 0xFF) {
							argb = hat;
						} else if (alpha != 0) {
							argb = over(hat, base, alpha);
						}
					}
					out.setColorArgb(x, y, argb);
				}
			}
			NativeImage result = out;
			out = null;
			return result;
		} catch (Throwable t) {
			return null;
		} finally {
			close(skin);
			close(out);
		}
	}

	/** Straight src-over composite of an opaque background with a partially transparent overlay. */
	private static int over(int src, int dst, int alpha) {
		int inv = 255 - alpha;
		int r = (((src >> 16) & 0xFF) * alpha + ((dst >> 16) & 0xFF) * inv) / 255;
		int g = (((src >> 8) & 0xFF) * alpha + ((dst >> 8) & 0xFF) * inv) / 255;
		int b = ((src & 0xFF) * alpha + (dst & 0xFF) * inv) / 255;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Hands the finished image to the render thread; it owns the image from there on. */
	private static void publish(Job job, NativeImage image) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			close(image);
			IN_FLIGHT.remove(job.cacheKey());
			return;
		}
		Identifier id = identifierFor(job.cacheKey());
		if (id == null) {
			close(image);
			fail(job.cacheKey());
			return;
		}
		int width = image.getWidth();
		int height = image.getHeight();

		// registerTexture allocates a GPU texture - render thread only, or the game hard-crashes.
		client.execute(() -> {
			TextureManager manager = textureManager();
			if (manager == null) {
				close(image);
				IN_FLIGHT.remove(job.cacheKey());
				return;
			}
			try {
				NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> id.toString(), image);
				texture.setFilter(false, false);
				texture.setClamp(true);
				manager.registerTexture(id, texture);
			} catch (Throwable t) {
				close(image);
				fail(job.cacheKey());
				return;
			}

			List<Identifier> evicted = new ArrayList<>();
			synchronized (CACHE) {
				CACHE.put(job.cacheKey(), new Entry(id, width, height, job.player()));
				while (CACHE.size() > MAX_TEXTURES) {
					Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
					if (!it.hasNext()) break;
					Map.Entry<String, Entry> eldest = it.next();
					it.remove();
					if (!eldest.getValue().id.equals(id)) evicted.add(eldest.getValue().id);
				}
			}
			for (Identifier dead : evicted) {
				try {
					manager.destroyTexture(dead);
				} catch (Throwable ignored) {
				}
			}
			FAILED.remove(job.cacheKey());
			IN_FLIGHT.remove(job.cacheKey());
		});
	}

	private static void fail(String cacheKey) {
		long[] previous = FAILED.get(cacheKey);
		int steps = previous == null ? 0 : (int) previous[1];
		steps = Math.min(steps + 1, BACKOFF_MAX_STEPS);
		long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << (steps - 1));
		FAILED.put(cacheKey, new long[]{System.currentTimeMillis() + delay, steps});
		IN_FLIGHT.remove(cacheKey);
	}

	// ---------------------------------------------------------- disk cache

	private static Path cacheDir() {
		try {
			return FabricLoader.getInstance().getConfigDir().resolve("afkable-heads");
		} catch (Throwable t) {
			return null;
		}
	}

	private static Path diskFile(String cacheKey) {
		Path dir = cacheDir();
		return dir == null ? null : dir.resolve(cacheKey + ".png");
	}

	/** Returns the composed head straight off disk, or {@code null} to force a network pull. */
	private static NativeImage readDisk(Job job) {
		Path file = diskFile(job.cacheKey());
		if (file == null) return null;
		try {
			if (!Files.isRegularFile(file)) return null;
			if (job.player()) {
				long age = System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis();
				if (age > PLAYER_TTL_MS) return null;
			}
			byte[] bytes = Files.readAllBytes(file);
			if (bytes.length < 16 || bytes.length > MAX_BODY_BYTES || !isPng(bytes)) {
				deleteQuietly(file);
				return null;
			}
			NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
			int w = image.getWidth();
			if (w < 8 || w > 64 || w != image.getHeight()) {
				close(image);
				deleteQuietly(file);
				return null;
			}
			return image;
		} catch (Throwable t) {
			deleteQuietly(file);
			return null;
		}
	}

	private static void writeDisk(String cacheKey, NativeImage image) {
		Path file = diskFile(cacheKey);
		if (file == null) return;
		try {
			Files.createDirectories(file.getParent());
			image.writeTo(file);
		} catch (Throwable ignored) {
		}
	}

	private static void wipeDisk() {
		Path dir = cacheDir();
		if (dir == null) return;
		try {
			if (!Files.isDirectory(dir)) return;
			try (var stream = Files.list(dir)) {
				stream.filter(p -> p.getFileName().toString().endsWith(".png"))
						.forEach(HeadTextures::deleteQuietly);
			}
		} catch (Throwable ignored) {
		}
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (Throwable ignored) {
		}
	}

	// -------------------------------------------------------------- upkeep

	/** Retires expired negative-cache entries and day-old player skins. */
	private static void sweep(long now) {
		FAILED.entrySet().removeIf(e -> now - e.getValue()[0] > BACKOFF_MAX_MS * 2);

		List<Identifier> stale = new ArrayList<>();
		synchronized (CACHE) {
			Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
			while (it.hasNext()) {
				Entry entry = it.next().getValue();
				if (entry.player && now - entry.created > PLAYER_TTL_MS) {
					it.remove();
					stale.add(entry.id);
				}
			}
		}
		if (stale.isEmpty()) return;
		TextureManager manager = textureManager();
		if (manager == null) return;
		for (Identifier id : stale) {
			try {
				manager.destroyTexture(id);
			} catch (Throwable ignored) {
			}
		}
	}

	// -------------------------------------------------------------- helpers

	private static HttpClient client() {
		HttpClient local = http;
		if (local == null) {
			synchronized (HeadTextures.class) {
				local = http;
				if (local == null) {
					local = HttpClient.newBuilder()
							.connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
							.followRedirects(HttpClient.Redirect.NORMAL)
							.build();
					http = local;
				}
			}
		}
		return local;
	}

	private static TextureManager textureManager() {
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			return client == null ? null : client.getTextureManager();
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean isAllowed(URI uri) {
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null) return false;
		if (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http")) return false;
		return ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT));
	}

	private static boolean isPng(byte[] data) {
		return data.length >= 8
				&& (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
				&& (data[4] & 0xFF) == 0x0D && (data[5] & 0xFF) == 0x0A
				&& (data[6] & 0xFF) == 0x1A && (data[7] & 0xFF) == 0x0A;
	}

	/**
	 * Cleans the API's URL: trims it, upgrades the plain-http texture host to https, and accepts
	 * a bare 64-char skin hash in either argument.
	 *
	 * @return an absolute http(s) URL, or {@code null} when there is nothing fetchable
	 */
	private static String normalizeUrl(String key, String textureUrl) {
		String raw = textureUrl == null ? "" : textureUrl.trim();
		if (raw.isEmpty()) raw = key == null ? "" : key.trim();
		if (raw.isEmpty()) return null;
		if (isHash(raw)) return "https://textures.minecraft.net/texture/" + raw.toLowerCase(Locale.ROOT);
		if (raw.startsWith("http://")) return "https://" + raw.substring("http://".length());
		if (raw.startsWith("https://")) return raw;
		return null;
	}

	private static boolean isHash(String value) {
		int len = value.length();
		if (len < 32 || len > 64) return false;
		for (int i = 0; i < len; i++) {
			char c = Character.toLowerCase(value.charAt(i));
			if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
		}
		return true;
	}

	/** Cache identity is the texture itself, so mobs sharing one skin share one download. */
	private static String cacheKeyFor(String url) {
		int slash = url.lastIndexOf('/');
		String tail = slash >= 0 && slash + 1 < url.length() ? url.substring(slash + 1) : "";
		int dot = tail.indexOf('.');
		if (dot > 0) tail = tail.substring(0, dot);
		if (isHash(tail)) return tail.toLowerCase(Locale.ROOT);
		return "u" + sha1(url);
	}

	private static String sha1(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] out = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(out.length * 2);
			for (byte b : out) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			return sb.toString();
		} catch (Throwable t) {
			return Integer.toHexString(value.hashCode());
		}
	}

	private static String sanitizeName(String username) {
		if (username == null) return null;
		String name = SortManager.normalizeText(username).trim().toLowerCase(Locale.ROOT);
		if (name.isEmpty() || name.length() > 16) return null;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
			if (!ok) return null;
		}
		return name;
	}

	/** Identifier paths only allow {@code [a-z0-9/._-]}; our keys are hex or {@code player_<name>}. */
	private static Identifier identifierFor(String cacheKey) {
		StringBuilder sb = new StringBuilder(cacheKey.length());
		for (int i = 0; i < cacheKey.length() && sb.length() < 80; i++) {
			char c = Character.toLowerCase(cacheKey.charAt(i));
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
			sb.append(ok ? c : '_');
		}
		if (sb.length() == 0) return null;
		try {
			return Identifier.of(NAMESPACE, "heads/" + sb);
		} catch (Throwable t) {
			return null;
		}
	}

	private static void close(NativeImage image) {
		if (image == null) return;
		try {
			image.close();
		} catch (Throwable ignored) {
		}
	}
}
