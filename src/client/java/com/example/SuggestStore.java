package com.example;

import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dev-side suggestion archive: ntfy only keeps ~12h, so the dev's client
 *  polls in the background and keeps everything locally for 14 days. */
public class SuggestStore {

	private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"(.*?)\"");
	private static final Pattern MSG = Pattern.compile("\"message\"\\s*:\\s*\"(.*?)\"");
	private static final long KEEP_MS = 14L * 24 * 60 * 60 * 1000;

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("afkable-suggestions.txt");
	}

	public static Map<String, String[]> load() {
		Map<String, String[]> entries = new LinkedHashMap<>();
		try {
			if (!Files.exists(file())) return entries;
			long now = System.currentTimeMillis();
			for (String line : Files.readAllLines(file())) {
				String[] parts = line.split("\\|", 3);
				if (parts.length != 3) continue;
				try {
					long time = Long.parseLong(parts[0]);
					if (now - time <= KEEP_MS) {
						entries.put(parts[1], new String[]{parts[0], parts[2]});
					}
				} catch (NumberFormatException ignored) {
				}
			}
		} catch (Exception ignored) {
		}
		return entries;
	}

	public static void save(Map<String, String[]> entries) {
		try {
			List<String> lines = new ArrayList<>();
			for (Map.Entry<String, String[]> entry : entries.entrySet()) {
				lines.add(entry.getValue()[0] + "|" + entry.getKey() + "|" + entry.getValue()[1]);
			}
			Files.write(file(), lines);
		} catch (Exception ignored) {
		}
	}

	public static void delete(String id) {
		Map<String, String[]> entries = load();
		entries.remove(id);
		save(entries);
	}

	/** Polls ntfy and merges anything new into the local archive. */
	public static void poll(Runnable onDone) {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://ntfy.sh/" + SuggestScreen.SUGGEST_TOPIC + "/json?poll=1&since=12h"))
				.timeout(Duration.ofSeconds(8))
				.GET().build();
		HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response -> {
					Map<String, String[]> entries = load();
					boolean changed = false;
					for (String line : response.body().split("\n")) {
						Matcher idMatch = ID.matcher(line);
						Matcher msgMatch = MSG.matcher(line);
						if (idMatch.find() && msgMatch.find() && !msgMatch.group(1).isBlank()) {
							if (!entries.containsKey(idMatch.group(1))) {
								entries.put(idMatch.group(1),
										new String[]{String.valueOf(System.currentTimeMillis()), msgMatch.group(1)});
								changed = true;
							}
						}
					}
					if (changed) save(entries);
					if (onDone != null) onDone.run();
				});
	}
}
