package com.example;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NtfyNet {

	private static final Pattern MESSAGE_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");
	private static final int CYCLE_TICKS = 240;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private int tickCounter = 0;
	private long backoffUntilMs = 0;
	private String lastServer = null;
	private boolean busy = false;

	public void syncNow() {
		tickCounter = CYCLE_TICKS - 2;
		backoffUntilMs = 0;
	}

	public void tick(MinecraftClient client) {
		if (!ForgeCalcConfig.clientEnabled || !ForgeCalcConfig.starSync) return;
		if (client.player == null || client.world == null) {
			lastServer = null;
			return;
		}
		if (client.isInSingleplayer()) return;

		String server = serverKey(client);
		if (server == null) return;

		if (!server.equals(lastServer)) {
			lastServer = server;
			tickCounter = CYCLE_TICKS - 5;
		}

		tickCounter++;
		if (tickCounter < CYCLE_TICKS) return;
		tickCounter = 0;

		if (System.currentTimeMillis() < backoffUntilMs || busy) return;

		String topic = topicFor(server);
		String uuid = client.player.getUuidAsString().toLowerCase(Locale.ROOT);
		String name = client.player.getName().getString();

		busy = true;
		publish(topic, uuid + "," + name)
				.thenCompose(ok -> poll(topic))
				.whenComplete((roster, error) -> {
					busy = false;
					if (error != null || roster == null) return;
					roster.add(uuid);
					roster.add(name.toLowerCase(Locale.ROOT));
					client.execute(() -> ForgeOreCalculatorClient.replaceClientUsers(roster));
				});
	}

	private java.util.concurrent.CompletableFuture<Boolean> publish(String topic, String body) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + topic))
				.timeout(Duration.ofSeconds(6))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
				.handle((response, error) -> {
					if (error == null && response.statusCode() == 429) {
						rateLimited();
					}
					return error == null;
				});
	}

	private java.util.concurrent.CompletableFuture<Set<String>> poll(String topic) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + topic + "/json?poll=1&since=40s"))
				.timeout(Duration.ofSeconds(6))
				.GET().build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.handle((response, error) -> {
					if (error != null) return null;
					if (response.statusCode() == 429) {
						rateLimited();
						return null;
					}
					return parse(response.body());
				});
	}

	private Set<String> parse(String body) {
		Set<String> roster = new HashSet<>();
		if (body == null) return roster;
		for (String line : body.split("\n")) {
			Matcher matcher = MESSAGE_PATTERN.matcher(line);
			if (!matcher.find()) continue;
			String[] parts = matcher.group(1).split(",");
			if (parts.length >= 2) {
				String uuid = parts[0].trim().toLowerCase(Locale.ROOT);
				String name = parts[1].trim();
				if (!uuid.isBlank() && !name.isBlank() && name.length() <= 20) {
					roster.add(uuid);
					roster.add(name.toLowerCase(Locale.ROOT));
					ForgeOreCalculatorClient.recordKnownUser(uuid, name);
				}
			}
		}
		return roster;
	}

	private void rateLimited() {
		backoffUntilMs = System.currentTimeMillis() + 60_000;
	}

	private static String serverKey(MinecraftClient client) {
		ServerInfo info = client.getCurrentServerEntry();
		if (info == null || info.address == null || info.address.isBlank()) return null;
		return normalizeAddress(info.address);
	}

	private static String normalizeAddress(String address) {
		String result = address.trim().toLowerCase(Locale.ROOT);
		if (!result.startsWith("[") && result.endsWith(":25565")) {
			result = result.substring(0, result.length() - 6);
		}
		while (result.endsWith(".")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

	private static String topicFor(String serverKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(serverKey.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder("afkable-");
			for (int i = 0; i < 8; i++) {
				hex.append(String.format("%02x", hash[i]));
			}
			return hex.toString();
		} catch (Exception e) {
			return "afkable-fallback";
		}
	}
}
