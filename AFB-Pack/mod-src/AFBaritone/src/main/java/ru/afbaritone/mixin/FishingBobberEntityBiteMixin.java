package ru.afbaritone.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.afbaritone.FishingManager;

/**
 * Перехват handleStatus: сервер присылает статус 31 (FISHING_BOBBER_BITES)
 * ровно в момент поклёвки. Это самый ранний и надёжный сигнал.
 */
@Mixin(FishingBobberEntity.class)
public class FishingBobberEntityBiteMixin {

    @Inject(method = "handleStatus(B)V", at = @At("HEAD"))
    private void afb$onHandleStatus(byte status, CallbackInfo ci) {
        if (status == 31) {
            FishingManager.onServerBiteSignal();
        }
    }
}
