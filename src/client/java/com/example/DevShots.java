package com.example;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.ScreenshotRecorder;

import java.util.List;
import java.util.function.Supplier;

/**
 * Dev-only screenshot run ({@code gradlew runShots}, which sets {@code -Dafkable.shots=true}).
 * Creates a fresh world, opens each client screen, saves run/screenshots/afk-*.png, then quits.
 * Never active in a normal install.
 */
final class DevShots {

	private record Shot(String name, Supplier<Screen> screen) {
	}

	private static final List<Shot> SHOTS = List.of(
			new Shot("bite", () -> {
				BiteAlert.trigger();
				return null;
			}),
			new Shot("menu", ForgeCalcMenuScreen::new),
			new Shot("settings-hud", () -> new ForgeCalcSettingsScreen(null)),
			new Shot("settings-general", () -> settingsTab(1)),
			new Shot("versions", () -> new VersionsScreen(null)),
			new Shot("devpanel", () -> new DevPanelScreen(null)),
			new Shot("devpost", () -> new DevPostScreen(null)),
			new Shot("scripts", () -> new ScriptScreen(null)),
			new Shot("trash", () -> new TrashScreen(null)),
			new Shot("vault", () -> new VaultSearchScreen(null)),
			new Shot("buttons", () -> new ButtonsScreen(null)),
			new Shot("devlogs", () -> new DevLogsScreen(null)),
			new Shot("stock", () -> new StockNotifierScreen(null)),
			new Shot("stats", () -> new StatsScreen(null)),
			new Shot("gang", () -> new GangChatScreen(null)),
			new Shot("keys", () -> new KeybindsScreen(null)),
			new Shot("hudedit", () -> new HudEditScreen(null)),
			new Shot("upgrader", () -> new UpgradeAutoScreen(null)),
			new Shot("activity", () -> new LiveActivityScreen(null)),
			new Shot("suggest", () -> new SuggestScreen(null)));

	private static int stage = 0;
	private static int wait = 0;
	private static int index = 0;

	private DevShots() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(DevShots::tick);
	}

	private static Screen settingsTab(int tab) {
		ForgeCalcSettingsScreen screen = new ForgeCalcSettingsScreen(null);
		try {
			var field = ForgeCalcSettingsScreen.class.getDeclaredField("tab");
			field.setAccessible(true);
			field.setInt(screen, tab);
		} catch (ReflectiveOperationException e) {
			log("could not switch settings tab: " + e);
		}
		return screen;
	}

	private static void tick(MinecraftClient client) {
		if (wait > 0) {
			wait--;
			return;
		}
		switch (stage) {
			case 0 -> {
				if (client.currentScreen instanceof TitleScreen) {
					CreateWorldScreen.show(client, client.currentScreen);
					stage = 1;
					wait = 40;
				}
			}
			case 1 -> {
				if (client.currentScreen instanceof CreateWorldScreen create) {
					for (var child : create.children()) {
						if (child instanceof ButtonWidget button
								&& button.getMessage().getString().toLowerCase().contains("create")) {
							button.onPress();
							stage = 2;
							log("creating world");
							return;
						}
					}
				}
			}
			case 2 -> {
				if (client.player != null && client.world != null && client.currentScreen == null) {
					stage = 3;
					wait = 100;
					log("in world as " + client.player.getName().getString());
					selfTests();
				}
			}
			case 3 -> {
				if (index > 0) {
					String name = SHOTS.get(index - 1).name();
					ScreenshotRecorder.saveScreenshot(client.runDirectory, "afk-" + name + ".png",
							client.getFramebuffer(), 1, text -> log(name + ": " + text.getString()));
				}
				if (index >= SHOTS.size()) {
					log("done");
					stage = 4;
					wait = 20;
					return;
				}
				Shot shot = SHOTS.get(index++);
				try {
					client.setScreen(shot.screen().get());
				} catch (Throwable t) {
					log(shot.name() + " FAILED to open: " + t);
				}
				wait = 30;
			}
			case 4 -> client.scheduleStop();
			default -> {
			}
		}
	}

	/** Parser checks plus a riddle round replayed exactly as AFKable printed it in chat. */
	private static void selfTests() {
		for (String line : ChatGameHelper.selfTest().split("\n")) {
			if (!line.isBlank()) log("selftest " + line);
		}
		// dev run only: start from an empty answer book so "unknown" really is unknown
		ChatGameHelper.forgetAll();
		ChatGameHelper.clear();
		feed("RIDDLE · first right answer wins", "I go up but never come down.", "Prize: $4.9B · 60s to answer");
		log("riddle seeded -> " + ChatGameHelper.answer() + (ChatGameHelper.answer().equals("AGE") ? " OK" : " FAIL"));
		feed("RIDDLE · nobody got it", "It was AGE");

		feed("RIDDLE · first right answer wins", "What has a tongue but cannot talk?", "Prize: $1B · 60s to answer");
		log("riddle unknown -> '" + ChatGameHelper.answer() + "' (expect empty)");
		feed("HINT · s__e");
		log("riddle hint s__e -> '" + ChatGameHelper.answer() + "'");
		feed("RIDDLE · nobody got it", "It was SHOE");
		feed("RIDDLE · first right answer wins", "What has a tongue but cannot talk?");
		log("riddle learned -> " + ChatGameHelper.answer() + (ChatGameHelper.answer().equalsIgnoreCase("shoe") ? " OK" : " FAIL"));
		feed("RIDDLE · nobody got it", "It was SHOE");
		ForgeOreCalculatorClient.INSTANCE.detectSummaryChat("SUMMARY Last 5 minutes: +$24.52B (#1)");
		log("income -> " + ForgeOreCalculatorClient.INSTANCE.incomeLine().replaceAll("§.", ""));
		log("money 66.8No -> " + ForgeOreCalculatorClient.parseMoney("66.8No") + ", 24.52B -> " + ForgeOreCalculatorClient.parseMoney("24.52B")
				+ ", format 6.68e31 -> " + ForgeOreCalculatorClient.formatMoney(6.68e31));
	}

	private static void feed(String... lines) {
		for (String line : lines) ChatGameHelper.onChat(line);
	}

	private static void log(String message) {
		System.out.println("[AFK-SHOTS] " + message);
	}
}
