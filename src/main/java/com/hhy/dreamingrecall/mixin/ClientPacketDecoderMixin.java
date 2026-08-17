package com.hhy.dreamingrecall.mixin;

import com.hhy.dreamingrecall.client.recording.ClientRecordingManager;
import com.hhy.dreamingrecall.client.recording.ServerPacketTrackUploader;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
abstract class ClientPacketDecoderMixin {
    @Shadow
    @Final
    private ProtocolInfo<?> protocolInfo;

    @Unique
    private byte[] dreamingrecall$frame;
    @Unique
    private int dreamingrecall$outputSize;

    @Inject(method = "decode", at = @At("HEAD"))
    private void dreamingrecall$copyInboundFrame(
            ChannelHandlerContext context,
            ByteBuf input,
            List<Object> output,
            CallbackInfo callback
    ) {
        dreamingrecall$frame = null;
        if (protocolInfo.flow() != PacketFlow.CLIENTBOUND || input.readableBytes() == 0) {
            return;
        }
        dreamingrecall$outputSize = output.size();
        byte[] frame = new byte[input.readableBytes()];
        input.getBytes(input.readerIndex(), frame);
        dreamingrecall$frame = frame;
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void dreamingrecall$recordInboundFrame(
            ChannelHandlerContext context,
            ByteBuf input,
            List<Object> output,
            CallbackInfo callback
    ) {
        byte[] frame = dreamingrecall$frame;
        dreamingrecall$frame = null;
        if (frame == null || output.size() <= dreamingrecall$outputSize) {
            return;
        }
        Object decoded = output.get(output.size() - 1);
        if (decoded instanceof Packet<?> packet) {
            ClientRecordingManager.INSTANCE.inboundPacket(protocolInfo, packet, frame);
            ServerPacketTrackUploader.INSTANCE.inboundPacket(protocolInfo, packet, frame);
        }
    }
}
