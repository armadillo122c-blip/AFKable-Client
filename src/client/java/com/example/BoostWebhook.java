package com.example;

import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Posts a Discord webhook when a global boost starts.
 * <p>
 * The webhook URL and role IDs live in config/afkable-boost-webhook.txt - they
 * are NEVER bundled in the jar. A leaked webhook can only spam one channel and
 * is trivially replaced, unlike a bot token.
 */
public class BoostWebhook {

	// a chat line that mentions a boost AND carries a multiplier like "5x"
	private static final Pattern MULTIPLIER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*x");

	private static String webhookUrl = "";
	private static String boostRole = "";
	private static String boost5xRole = "";
	private static String triggerWord = "boost";
	private static boolean enabled = false;
	private static boolean loaded = false;

	private static String lastBoostText = "";
	private static long lastFireTime = 0;
	private static final long COOLDOWN_MS = 3 * 60 * 1000;

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-boost-webhook.txt");
	}

	public static void ensureFile() {
		try {
			if (Files.exists(file())) return;
			Files.createDirectories(file().getParent());
			Files.writeString(file(), """
					# AFKABLE CLIENT - Discord boost alerts
					#
					# Fill these in to ping Discord when a global boost starts.
					# Turn it on by setting enabled=true.
					#
					# How to get the webhook URL:
					#   Channel > Edit > Integrations > Webhooks > New Webhook > Copy URL
					# How to get role IDs:
					#   Enable Developer Mode, right-click a role > Copy Role ID
					#   (each role must allow "anyone to @mention this role")
					#
					enabled=false
					webhook=
					# role pinged for ANY boost:
					boostRole=
					# extra role pinged only when the boost is 5x or higher:
					boost5xRole=
					# the word that must appear in chat for it to count as a boost:
					trigger=boost
					""");
		} catch (Exception ignored) {
		}
	}

	public static void load() {
		loaded = true;
		ensureFile();
		try {
			Properties props = new Properties();
			props.load(Files.newBufferedReader(file()));
			enabled = Boolean.parseBoolean(props.getProperty("enabled", "false").trim());
			webhookUrl = props.getProperty("webhook", "").trim();
			boostRole = props.getProperty("boostRole", "").trim();
			boost5xRole = props.getProperty("boost5xRole", "").trim();
			triggerWord = props.getProperty("trigger", "boost").trim().toLowerCase();
			if (triggerWord.isEmpty()) triggerWord = "boost";
		} catch (Exception ignored) {
		}
	}

	/** Called for every chat line the client sees. */
	public static void onChat(String rawMessage) {
		if (!loaded) load();
		if (!enabled || webhookUrl.isEmpty() || !webhookUrl.startsWith("https://")) return;

		String text = SortManager.normalizeText(rawMessage).toLowerCase();
		if (!text.contains(triggerWord)) return;

		// ignore the idle "no current global boost" line and boost-ended lines
		if (text.contains("no current") || text.contains("no active")
				|| text.contains("ended") || text.contains("expired") || text.contains("ran out")) {
			return;
		}

		// must actually be a start, and must carry a multiplier
		Matcher m = MULTIPLIER.matcher(text);
		if (!m.find()) return;
		double multiplier;
		try {
			multiplier = Double.parseDouble(m.group(1));
		} catch (NumberFormatException e) {
			return;
		}
		if (multiplier < 1.5) return;

		long now = System.currentTimeMillis();
		String clean = rawMessage.replaceAll("(?i)§[0-9a-fk-or]", "").trim();
		if (clean.equalsIgnoreCase(lastBoostText) && now - lastFireTime < COOLDOWN_MS) return;
		if (now - lastFireTime < 15000) return; // hard floor against burst spam
		lastBoostText = clean;
		lastFireTime = now;

		fire(multiplier, clean);
	}

	private static void fire(double multiplier, String boostText) {
		StringBuilder pings = new StringBuilder();
		if (!boostRole.isEmpty()) pings.append("<@&").append(boostRole).append("> ");
		if (multiplier >= 5.0 && !boost5xRole.isEmpty()) pings.append("<@&").append(boost5xRole).append("> ");

		String content = pings + "\u26a1 **Global Boost active!** " + trimMultiplier(multiplier) + "x is live \u2014 " + escape(boostText);

		String allowedRoles = buildAllowedRoles();
		String body = "{\"content\":\"" + escape(content) + "\","
				+ "\"allowed_mentions\":{\"parse\":[],\"roles\":[" + allowedRoles + "]}}";

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(webhookUrl))
				.timeout(Duration.ofSeconds(8))
				.header("content-type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.discarding());
	}

	private static String buildAllowedRoles() {
		StringBuilder roles = new StringBuilder();
		if (!boostRole.isEmpty()) roles.append("\"").append(boostRole).append("\"");
		if (!boost5xRole.isEmpty()) {
			if (roles.length() > 0) roles.append(",");
			roles.append("\"").append(boost5xRole).append("\"");
		}
		return roles.toString();
	}

	private static String trimMultiplier(double value) {
		if (value == Math.floor(value)) return String.valueOf((long) value);
		return String.valueOf(value);
	}

	private static String escape(String raw) {
		return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
	}
}
