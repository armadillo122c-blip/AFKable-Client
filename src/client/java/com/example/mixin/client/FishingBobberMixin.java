package com.example.mixin.client;

import com.example.BiteAlert;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla syncs "a fish is on the hook" to the client; this is the moment to right-click. */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberMixin {

	@Shadow
	private boolean caughtFish;

	@Inject(method = "onTrackedDataSet", at = @At("TAIL"))
	private void forgecalc$biteAlert(TrackedData<?> data, CallbackInfo ci) {
		if (!caughtFish) return;
		FishingBobberEntity self = (FishingBobberEntity) (Object) this;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && self.getPlayerOwner() == client.player) {
			BiteAlert.trigger();
		}
	}
}
