package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks published GitHub releases, verifies the asset digest, then replaces the mod after shutdown. */
public final class AutoUpdater {
	private static final String RELEASE_API = "https://api.github.com/repos/armadillo122c-blip/AFKable-Client/releases/latest";
	private static final String ASSET_NAME = "AFKableClient.jar";
	private static final long MAX_DOWNLOAD_BYTES = 100L * 1024L * 1024L;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "afkable-update-check");
		thread.setDaemon(true);
		return thread;
	});
	private static final AtomicBoolean CHECKING = new AtomicBoolean();
	private static volatile String status = "Checking GitHub releases...";
	private static volatile boolean readyNoticePending;
	private static boolean noticeShown;
	private static boolean started;
	private static boolean lastInWorld;
	private static boolean worldStateKnown;
	private static int ticksUntilPeriodicCheck;
	private static final int CHECK_INTERVAL_TICKS = 20 * 60 * 10;

	private AutoUpdater() {
	}

	public static synchronized void start() {
		if (started) return;
		started = true;
		checkNow();
	}

	public static void checkNow() {
		if (!CHECKING.compareAndSet(false, true)) return;
		status = "Checking GitHub releases...";
		WORKER.execute(() -> {
			try {
				checkAndDownload();
			} catch (Exception ignored) {
				status = "Update check failed; will retry next launch";
			} finally {
				CHECKING.set(false);
			}
		});
	}

	public static String status() {
		return status;
	}

	public static void tick(MinecraftClient client) {
		boolean inWorld = client.player != null && client.world != null;
		if (worldStateKnown && inWorld != lastInWorld) {
			checkNow();
			ticksUntilPeriodicCheck = CHECK_INTERVAL_TICKS;
		}
		lastInWorld = inWorld;
		worldStateKnown = true;
		if (ticksUntilPeriodicCheck <= 0) {
			ticksUntilPeriodicCheck = CHECK_INTERVAL_TICKS;
			checkNow();
		} else {
			ticksUntilPeriodicCheck--;
		}

		if (!readyNoticePending || noticeShown || client.player == null || client.world == null) return;
		readyNoticePending = false;
		noticeShown = true;
		ForgeOreCalculatorClient instance = ForgeOreCalculatorClient.INSTANCE;
		if (instance != null) instance.notifyChat(client, "§a" + status);
	}

	private static void checkAndDownload() throws Exception {
		var client = java.net.http.HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
				.build();
		var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(RELEASE_API))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "AFKableClient-Updater")
				.GET().build();
		var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			status = "No GitHub release published yet";
			return;
		}
		if (response.statusCode() != 200) throw new IOException("GitHub returned HTTP " + response.statusCode());

		JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
		String tag = release.get("tag_name").getAsString().replaceFirst("^[vV]", "");
		String current = FabricLoader.getInstance().getModContainer("forge-ore-calculator")
				.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
		if (DevLogsScreen.compareVersions(tag, current) <= 0) {
			status = "You're up to date (v" + current + ")";
			return;
		}

		JsonObject asset = findAsset(release.getAsJsonArray("assets"));
		if (asset == null) throw new IOException("release has no " + ASSET_NAME);
		String url = asset.get("browser_download_url").getAsString();
		String digest = asset.has("digest") && !asset.get("digest").isJsonNull()
				? asset.get("digest").getAsString() : "";
		if (!url.startsWith("https://github.com/armadillo122c-blip/AFKable-Client/releases/download/")
				|| !digest.matches("sha256:[0-9a-fA-F]{64}")) {
			throw new IOException("release asset URL or SHA-256 digest is missing or invalid");
		}

		Path target = installedJar();
		if (target == null) {
			status = "Update found, but automatic install is disabled in development runs";
			return;
		}
		if (Files.size(target) > MAX_DOWNLOAD_BYTES) throw new IOException("installed mod is unexpectedly large");

		var downloadRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.timeout(Duration.ofSeconds(60)).header("User-Agent", "AFKableClient-Updater").GET().build();
		var download = client.send(downloadRequest, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
		byte[] bytes = download.body();
		if (download.statusCode() != 200 || bytes.length == 0 || bytes.length > MAX_DOWNLOAD_BYTES) {
			throw new IOException("release download failed or exceeded the size limit");
		}
		String actualDigest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		if (!actualDigest.equalsIgnoreCase(digest)) throw new IOException("release SHA-256 check failed");

		Path updateFolder = FabricLoader.getInstance().getGameDir().resolve("updates");
		Files.createDirectories(updateFolder);
		Path pending = updateFolder.resolve("AFKableClient.jar.pending");
		Path temporary = updateFolder.resolve("AFKableClient.jar.download");
		Files.write(temporary, bytes);
		try {
			Files.move(temporary, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, pending, StandardCopyOption.REPLACE_EXISTING);
		}
		if (!scheduleInstall(target, pending, updateFolder)) {
			status = "v" + tag + " downloaded to .minecraft/updates; close Minecraft to finish the update";
			readyNoticePending = true;
			return;
		}
		status = "v" + tag + " downloaded; it will install when Minecraft closes";
		readyNoticePending = true;
	}

	private static JsonObject findAsset(JsonArray assets) {
		if (assets == null) return null;
		for (var element : assets) {
			if (element.isJsonObject() && ASSET_NAME.equals(element.getAsJsonObject().get("name").getAsString())) {
				return element.getAsJsonObject();
			}
		}
		return null;
	}

	private static Path installedJar() {
		return FabricLoader.getInstance().getModContainer("forge-ore-calculator")
				.map(container -> container.getOrigin().getPaths().stream()
						.map(path -> path.toAbsolutePath().normalize())
						.filter(path -> path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
						.filter(Files::isRegularFile)
						.findFirst().orElse(null))
				.orElse(null);
	}

	private static boolean scheduleInstall(Path target, Path pending, Path folder) throws IOException {
		long gamePid = ProcessHandle.current().pid();
		Path script = folder.resolve(isWindows() ? "install-afkable-update.cmd" : "install-afkable-update.sh");
		String contents;
		ProcessBuilder process;
		if (isWindows()) {
			contents = "@echo off\r\nsetlocal\r\n"
					+ "set \"GAMEPID=" + gamePid + "\"\r\n"
					+ "set \"TARGET=" + target + "\"\r\n"
					+ "set \"PENDING=" + pending + "\"\r\n"
					+ ":waitforgame\r\n"
					+ "tasklist /FI \"PID eq %GAMEPID%\" | findstr /C:\"%GAMEPID%\" >nul\r\n"
					+ "if not errorlevel 1 (timeout /t 2 /nobreak >nul & goto waitforgame)\r\n"
					+ "copy /y \"%PENDING%\" \"%TARGET%.new\" >nul\r\n"
					+ "if errorlevel 1 exit /b 1\r\n"
					+ "move /y \"%TARGET%.new\" \"%TARGET%\" >nul\r\n"
					+ "if errorlevel 1 exit /b 1\r\n"
					+ "del \"%PENDING%\"\r\ndel \"%~f0\"\r\n";
			Files.writeString(script, contents);
			process = new ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toString());
		} else if (isUnix()) {
			contents = "#!/bin/sh\n"
					+ "GAMEPID=" + gamePid + "\n"
					+ "TARGET=" + shellQuote(target.toString()) + "\n"
					+ "PENDING=" + shellQuote(pending.toString()) + "\n"
					+ "while kill -0 \"$GAMEPID\" 2>/dev/null; do sleep 2; done\n"
					+ "cp -f \"$PENDING\" \"$TARGET.new\" && mv -f \"$TARGET.new\" \"$TARGET\" && rm -f \"$PENDING\"\n"
					+ "rm -f \"$0\"\n";
			Files.writeString(script, contents);
			script.toFile().setExecutable(true);
			process = new ProcessBuilder("sh", script.toString());
		} else {
			return false;
		}
		process.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		return true;
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	private static boolean isUnix() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		return os.contains("linux") || os.contains("mac") || os.contains("unix");
	}

	private static String shellQuote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}
}
