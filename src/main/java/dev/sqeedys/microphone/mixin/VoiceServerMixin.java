package dev.sqeedys.microphone.mixin;

import de.maxhenkel.voicechat.voice.common.MicPacket;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.common.SoundPacket;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Server;
import dev.sqeedys.microphone.Microphone;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = Server.class, remap = false)
abstract class VoiceServerMixin {
    @Inject(method = "onMicPacket", at = @At("HEAD"), cancellable = true)
    private void microphone$muteMic(UUID speaker, MicPacket packet, CallbackInfo callback) {
        if (Microphone.suppressMic((Server) (Object) this, speaker, packet)) {
            callback.cancel();
        }
    }

    // This hook runs after Simple Voice Chat's speak permission and microphone plugin callbacks.
    @Inject(method = "processMicPacket", at = @At("HEAD"), cancellable = true)
    private void microphone$routeMic(ServerPlayer speaker, PlayerState state, MicPacket packet, CallbackInfo callback) {
        if (Microphone.routeMic((Server) (Object) this, speaker, state, packet)) {
            callback.cancel();
        }
    }

    @Inject(method = "sendSoundPacket", at = @At("HEAD"), cancellable = true)
    private void microphone$deafen(ServerPlayer sender, PlayerState senderState, ServerPlayer receiver,
                                          PlayerState receiverState, ClientConnection connection, SoundPacket<?> packet,
                                          String source, CallbackInfo callback) {
        if (Microphone.suppressSound((Server) (Object) this, sender, senderState, receiver, source)) {
            callback.cancel();
        }
    }
}
