package com.example;

import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ControlNet {

	public static final String TOPIC = "afkable-client-ctrl-armadillo122";
	private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"(.*?)\"");
	private static final Pattern MSG_PATTERN = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");
	private static final int POLL_TICKS = 900;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final Set<String> seenIds = new HashSet<>();
	private int tickCounter = POLL_TICKS - 40;
	private boolean firstPoll = true;
	private boolean busy = false;

	public interface Handler {
		void onNews(String text);

		void onBadges(Map<String, String> badges);

		void onScare(String target);

		void onAutoUpdate();

		void onPublicScript(String name, String content);

		void onGlobalScript(String name, String content);
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
					var scares = new java.util.ArrayList<String>();
					boolean autoUpdate = false;
					Map<String, String> scripts = new LinkedHashMap<>();
					Map<String, String> globalScripts = new LinkedHashMap<>();

					for (String line : response.body().split("\n")) {
						Matcher idMatcher = ID_PATTERN.matcher(line);
						Matcher msgMatcher = MSG_PATTERN.matcher(line);
						if (!idMatcher.find() || !msgMatcher.find()) continue;
						String id = idMatcher.group(1);
						String message = msgMatcher.group(1).replace("\\n", "\n");
						boolean unseen = seenIds.add(id);

						if (message.startsWith("badges:")) {
							latestBadges = parseBadges(message.substring(7));
						} else if (message.startsWith("news:") && unseen && !initial) {
							newsList.add(message.substring(5));
						} else if (message.startsWith("scare:") && unseen && !initial) {
							scares.add(message.substring(6).trim());
						} else if (message.equals("update:all") && unseen && !initial) {
							autoUpdate = true;
						} else if (message.startsWith("script:")) {
							int sep = message.indexOf('|');
							if (sep > 7) {
								String name = message.substring(7, sep).trim()
										.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
								if (!name.isBlank()) {
									scripts.put(name, message.substring(sep + 1));
								}
							}
						} else if (message.startsWith("global:") && unseen && !initial) {
							int sep = message.indexOf('|');
							if (sep > 7) {
								String name = message.substring(7, sep).trim()
										.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
								if (!name.isBlank()) {
									globalScripts.put(name, message.substring(sep + 1));
								}
							}
						}
					}
					if (seenIds.size() > 500) {
						seenIds.clear();
					}

					Map<String, String> badges = latestBadges;
					boolean forceAutoUpdate = autoUpdate;
					client.execute(() -> {
						if (badges != null) handler.onBadges(badges);
						for (String news : newsList) handler.onNews(news);
						for (String scare : scares) handler.onScare(scare);
						if (forceAutoUpdate) handler.onAutoUpdate();
						for (Map.Entry<String, String> script : scripts.entrySet()) {
							handler.onPublicScript(script.getKey(), script.getValue());
						}
						for (Map.Entry<String, String> script : globalScripts.entrySet()) {
							handler.onGlobalScript(script.getKey(), script.getValue());
						}
					});
				});
	}

	public void forcePollSoon() {
		tickCounter = POLL_TICKS - 20;
	}

	public void publish(String message) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + TOPIC))
				.timeout(Duration.ofSeconds(6))
				.POST(HttpRequest.BodyPublishers.ofString(message))
				.build();
		http.sendAsync(request, HttpResponse.BodyHandlers.discarding());
	}

	public void publishScript(String name, String content) {
		String safe = content.replace("\"", "'");
		publish("script:" + name + "|" + safe);
	}

	public void publishGlobalScript(String name, String content) {
		String safe = content.replace("\"", "'");
		publish("global:" + name + "|" + safe);
	}

	public void publishBadges(Map<String, String> badges) {
		StringBuilder body = new StringBuilder("badges:");
		for (Map.Entry<String, String> entry : badges.entrySet()) {
			if (body.length() > 7) body.append(";");
			body.append(entry.getKey()).append("=").append(entry.getValue());
		}
		publish(body.toString());
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
