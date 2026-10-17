package com.example.mixin.client;

import com.example.ForgeOreCalculatorClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

	@Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
	private void forgecalc$addClientIcon(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
		if (!com.example.ForgeCalcConfig.clientEnabled) return;
		String name = ForgeOreCalculatorClient.profileName(entry.getProfile());
		String uuid = ForgeOreCalculatorClient.profileUuid(entry.getProfile());
		String badge = ForgeOreCalculatorClient.badgePrefix(name, uuid);
		if (badge != null) {
			cir.setReturnValue(Text.empty()
					.append(Text.literal(badge))
					.append(cir.getReturnValue()));
		}
	}
}
