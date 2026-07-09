package com.killpv47.stealthaddon.mixin;

import com.killpv47.stealthaddon.features.FakePing;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FakePing mixin - lowers the latency value returned for the LOCAL player's
 * PlayerListEntry so the tab-list (and anything else that reads it) shows a
 * lower ping. Real network latency is untouched — this is display-only.
 */
@Mixin(PlayerListEntry.class)
public class PlayerListHudMixin {

    @Inject(method = "getLatency", at = @At("RETURN"), cancellable = true)
    private void stealthaddon$fakeLatency(CallbackInfoReturnable<Integer> cir) {
        try {
            PlayerListEntry self = (PlayerListEntry) (Object) this;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return;

            // Only fake OUR OWN ping, not other players'.
            if (!self.getProfile().getId().equals(mc.player.getUuid())) return;

            int real = cir.getReturnValueI();
            cir.setReturnValue(FakePing.applyFakePing(real));
        } catch (Throwable ignored) {
            // Fail silent - if mapping ever shifts, we simply skip.
        }
    }
}
