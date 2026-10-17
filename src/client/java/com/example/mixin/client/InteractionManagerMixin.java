package com.example.mixin.client;

import com.example.ForgeOreCalculatorClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class InteractionManagerMixin {

	@Inject(method = "breakBlock", at = @At("HEAD"), require = 0)
	private void forgecalc$onBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		ForgeOreCalculatorClient.onBlockBreak();
	}
}
