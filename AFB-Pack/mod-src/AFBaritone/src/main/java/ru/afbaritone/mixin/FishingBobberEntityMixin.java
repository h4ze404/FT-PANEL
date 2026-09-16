package ru.afbaritone.mixin;

import net.minecraft.entity.projectile.FishingBobberEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Доступ к приватному полю caughtFish: сервер присылает клиенту статус 31
 * (FISHING_BOBBER_BITES) ровно в момент поклёвки — это самый надёжный
 * сигнал для авто-подсечки.
 */
@Mixin(FishingBobberEntity.class)
public interface FishingBobberEntityMixin {

    @Accessor("caughtFish")
    boolean afb$getCaughtFish();
}
