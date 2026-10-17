package com.example.mixin.client;

import com.example.ForgeOreCalculatorClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseScrollMixin {

	@Inject(method = "onMouseScroll", at = @At("HEAD"), require = 0)
	private void forgecalc$captureScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.currentScreen instanceof HandledScreen<?>) {
			ForgeOreCalculatorClient.SCROLL_DELTA = vertical;
		}
	}
}
