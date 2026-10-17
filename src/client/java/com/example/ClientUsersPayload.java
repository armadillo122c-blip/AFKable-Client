package com.example;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import io.netty.buffer.ByteBuf;

public record ClientUsersPayload(String names) implements CustomPayload {

	public static final CustomPayload.Id<ClientUsersPayload> ID =
			new CustomPayload.Id<>(Identifier.of("forgecalc", "users"));

	public static final PacketCodec<ByteBuf, ClientUsersPayload> CODEC =
			PacketCodec.tuple(PacketCodecs.STRING, ClientUsersPayload::names, ClientUsersPayload::new);

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
