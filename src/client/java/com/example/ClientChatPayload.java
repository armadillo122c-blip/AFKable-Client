package com.example;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ClientChatPayload(String message) implements CustomPayload {

	public static final CustomPayload.Id<ClientChatPayload> ID =
			new CustomPayload.Id<>(Identifier.of("forgecalc", "chat"));

	public static final PacketCodec<ByteBuf, ClientChatPayload> CODEC =
			PacketCodec.tuple(PacketCodecs.STRING, ClientChatPayload::message, ClientChatPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
