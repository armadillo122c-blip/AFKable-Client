package com.example;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * Fishing bite alert: a fish on YOUR hook plays a ding and flashes "BITE!" over the crosshair,
 * so a Perfect Catch doesn't depend on staring at the bobber. It only tells you - reeling in is
 * still your right-click.
 */
public final class BiteAlert {

	private static final long SHOW_MS = 2200;
	private static long bitAt = 0;

	private BiteAlert() {
	}

	public static void trigger() {
		if (!ForgeCalcConfig.clientEnabled || !ForgeCalcConfig.biteAlert) return;
		long now = System.currentTimeMillis();
		// the tracker can re-send the same flag; one alert per bite
		if (now - bitAt < 800) return;
		bitAt = now;
		MinecraftClient client = MinecraftClient.getInstance();
		client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.6F, 1.0F));
	}

	/** True while the last bite is still on screen. */
	public static boolean showing() {
		return System.currentTimeMillis() - bitAt < SHOW_MS;
	}

	public static void render(MinecraftClient client, DrawContext ctx) {
		if (!showing()) return;
		float t = (System.currentTimeMillis() - bitAt) / (float) SHOW_MS;
		int alpha = (int) (255 * (1f - t * t));
		if (alpha < 8) return;
		int cx = client.getWindow().getScaledWidth() / 2;
		int cy = client.getWindow().getScaledHeight() / 2 - 34;
		String label = "§lBITE!";
		int w = client.textRenderer.getWidth(label) + 26;
		FarmScreen.roundedFill(ctx, cx - w / 2, cy - 6, cx + w / 2, cy + 14, (alpha / 2) << 24 | 0x0B111C);
		ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(label), cx, cy, alpha << 24 | 0x8FD3FF);
		ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal("right-click"), cx, cy + 18, (alpha * 3 / 4) << 24 | 0xFFFFFF);
	}
}
