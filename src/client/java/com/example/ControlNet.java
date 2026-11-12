package com.example;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev-to-client announcements (news popups and tab badges) over ntfy.sh.
 *
 * <p>The ntfy topic is public, so anyone can post to it. Every message therefore carries an
 * Ed25519 signature made with the dev's private key, which lives only on the dev's PC
 * ({@code ~/.afkable-dev-key}), never in the jar. Clients hold the public key and drop anything
 * unsigned, badly signed or older than {@link #MAX_AGE_SECONDS}. Messages can only ever show
 * text or set badges - nothing here runs scripts or commands.
 */
public class ControlNet {

	public static final String TOPIC = "afkable-client-ctrl-armadillo122";
	private static final String PUBLIC_KEY = "MCowBQYDK2VwAyEA2t9CUk4YZ5PV51ufkXolZEUR/yn0dWIqot88lWxccEY=";
	private static final Path DEV_KEY_FILE = Path.of(System.getProperty("user.home"), ".afkable-dev-key");
	private static final long MAX_AGE_SECONDS = 12 * 60 * 60;
	private static final long MAX_CLOCK_SKEW_SECONDS = 5 * 60;

	private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"(.*?)\"");
	private static final Pattern MSG_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"(s1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\"");
	private static final int POLL_TICKS = 900;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final Set<String> seenIds = new HashSet<>();
	private final PublicKey publicKey = loadPublicKey();
	private int tickCounter = POLL_TICKS - 40;
	private boolean firstPoll = true;
	private boolean busy = false;

	public interface Handler {
		void onNews(String text);

		void onBadges(Map<String, String> badges);

	}

	public void tick(MinecraftClient client, Handler handler) {
		if (client.player == null || client.world == null) return;
		tickCounter++;
		if (tickCounter < POLL_TICKS || busy) return;
		tickCounter = 0;

		boolean initial = firstPoll;
		firstPoll = false;
		busy = true;

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + TOPIC + "/json?poll=1&since=12h"))
				.timeout(Duration.ofSeconds(6))
				.GET().build();
		http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> {
					busy = false;
					if (error != null || response.statusCode() != 200) return;

					Map<String, String> latestBadges = null;
					var newsList = new java.util.ArrayList<String>();

					for (String line : response.body().split("\n")) {
						Matcher idMatcher = ID_PATTERN.matcher(line);
						Matcher msgMatcher = MSG_PATTERN.matcher(line);
						if (!idMatcher.find() || !msgMatcher.find()) continue;
						String message = verify(msgMatcher.group(1));
						if (message == null) continue;
						boolean unseen = seenIds.add(idMatcher.group(1));

						if (message.startsWith("badges:")) {
							latestBadges = parseBadges(message.substring(7));
						} else if (message.startsWith("news:") && unseen && !initial) {
							newsList.add(message.substring(5));
						}
					}
					if (seenIds.size() > 500) {
						seenIds.clear();
					}

					Map<String, String> badges = latestBadges;
					client.execute(() -> {
						if (badges != null) handler.onBadges(badges);
						for (String news : newsList) handler.onNews(news);
					});
				});
	}

	public void forcePollSoon() {
		tickCounter = POLL_TICKS - 20;
	}

	/** True when this PC holds the dev signing key, so {@link #publish} can work. */
	public static boolean canPublish() {
		return Files.isRegularFile(DEV_KEY_FILE);
	}

	/** Signs and posts a message. Returns false (and posts nothing) without the dev key. */
	public boolean publish(String message) {
		String signed = sign(message);
		if (signed == null) return false;
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + TOPIC))
				.timeout(Duration.ofSeconds(6))
				.POST(HttpRequest.BodyPublishers.ofString(signed))
				.build();
		http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
		return true;
	}

	public boolean publishBadges(Map<String, String> badges) {
		StringBuilder body = new StringBuilder("badges:");
		for (Map.Entry<String, String> entry : badges.entrySet()) {
			if (body.length() > 7) body.append(";");
			body.append(entry.getKey()).append("=").append(entry.getValue());
		}
		return publish(body.toString());
	}

	// ---------- SIGNING ----------

	// Wire format: s1.<base64url(epochSeconds + "\n" + message)>.<base64url(signature)>
	// Base64url keeps the whole thing free of characters ntfy's JSON would escape.

	private static String sign(String message) {
		try {
			byte[] der = Base64.getDecoder().decode(Files.readString(DEV_KEY_FILE).trim());
			PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
			byte[] payload = ((System.currentTimeMillis() / 1000) + "\n" + message).getBytes(StandardCharsets.UTF_8);
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(key);
			signer.update(payload);
			Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
			return "s1." + b64.encodeToString(payload) + "." + b64.encodeToString(signer.sign());
		} catch (Exception e) {
			return null;
		}
	}

	/** Returns the message if the signature is good and it is fresh, else null. */
	private String verify(String wire) {
		if (publicKey == null) return null;
		try {
			String[] parts = wire.split("\\.");
			if (parts.length != 3 || !parts[0].equals("s1")) return null;
			Base64.Decoder b64 = Base64.getUrlDecoder();
			byte[] payload = b64.decode(parts[1]);
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(publicKey);
			verifier.update(payload);
			if (!verifier.verify(b64.decode(parts[2]))) return null;

			String text = new String(payload, StandardCharsets.UTF_8);
			int newline = text.indexOf('\n');
			if (newline <= 0) return null;
			long sentAt = Long.parseLong(text.substring(0, newline));
			long now = System.currentTimeMillis() / 1000;
			if (sentAt < now - MAX_AGE_SECONDS || sentAt > now + MAX_CLOCK_SKEW_SECONDS) return null;
			return text.substring(newline + 1);
		} catch (Exception e) {
			return null;
		}
	}

	private static PublicKey loadPublicKey() {
		try {
			byte[] der = Base64.getDecoder().decode(PUBLIC_KEY);
			return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, String> parseBadges(String raw) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String entry : raw.split(";")) {
			String[] parts = entry.split("=");
			if (parts.length == 2 && !parts[0].isBlank()) {
				result.put(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim().toLowerCase(Locale.ROOT));
			}
		}
		return result;
	}
}
