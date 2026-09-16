package ru.afbaritone.mixin;

import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Сервер прислал пакет ресурс-паков — молча отвечаем «нет».
 * Экран подтверждения даже не открывается (банит и required-паки).
 */
@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {

    @Shadow
    public abstract void sendPacket(Packet<?> packet);

    @Inject(
            method = "onResourcePackSend(Lnet/minecraft/network/packet/s2c/common/ResourcePackSendS2CPacket;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void afb$autoDeclineResourcePack(ResourcePackSendS2CPacket packet, CallbackInfo ci) {
        sendPacket(new ResourcePackStatusC2SPacket(packet.id(), ResourcePackStatusC2SPacket.Status.DECLINED));
        ci.cancel();
    }
}
