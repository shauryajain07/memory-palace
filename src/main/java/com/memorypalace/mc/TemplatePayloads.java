package com.memorypalace.mc;

import com.memorypalace.MemoryPalace;
import com.memorypalace.core.editor.NoteTemplates;
import com.memorypalace.core.vault.NoteSummary;
import com.memorypalace.core.vault.Vault;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The templates the editor offers under "/": when an editor opens, the client asks for the templates that apply
 * to its note (see NoteTemplates), and the server answers with the readable, unencrypted ones, nearest first.
 */
public final class TemplatePayloads {
	private TemplatePayloads() {}

	public record Request(UUID noteId) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(MemoryPalace.id("template_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
				StreamCodec.composite(UUIDUtil.STREAM_CODEC, Request::noteId, Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Entry(String name, String body) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(200), Entry::name,
				ByteBufCodecs.stringUtf8(NoteTemplates.MAX_CHARS), Entry::body,
				Entry::new);
	}

	/** className and folder fill {{class}} and {{folder}}. */
	public record Templates(UUID noteId, String className, String folder, List<Entry> entries) implements CustomPacketPayload {
		public static final Type<Templates> TYPE = new Type<>(MemoryPalace.id("templates"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Templates> CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Templates::noteId,
				ByteBufCodecs.stringUtf8(200), Templates::className,
				ByteBufCodecs.stringUtf8(1024), Templates::folder,
				Entry.CODEC.apply(ByteBufCodecs.list(NoteTemplates.MAX_TEMPLATES)), Templates::entries,
				Templates::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static final RateLimiter RATE = new RateLimiter(1, 5);

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Templates.TYPE, Templates.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			Vault vault = PalaceServer.vault();
			if (vault == null || !RATE.tryTake(player.getUUID())) {
				return;
			}
			Optional<NoteSummary> note = vault.find(request.noteId()).filter(n -> PalaceAccess.canEdit(player, n));
			if (note.isEmpty()) {
				return;
			}
			String folder = note.get().folder();
			ServerPlayNetworking.send(player, new Templates(request.noteId(), NoteTemplates.className(folder), folder,
					templates(vault, player, folder)));
		});
	}

	/** The nearest template of each name, readable by player and not encrypted. */
	static List<Entry> templates(Vault vault, ServerPlayer player, String folder) {
		Map<String, Entry> byName = new LinkedHashMap<>();
		for (String candidate : NoteTemplates.searchFolders(folder)) {
			for (NoteSummary t : vault.listFolder(candidate)) {
				if (byName.size() >= NoteTemplates.MAX_TEMPLATES) {
					break;
				}
				String name = t.relativePath().substring(t.relativePath().lastIndexOf('/') + 1).replaceFirst("(?i)\\.md$", "");
				if (byName.containsKey(name) || t.encrypted() || !PalaceAccess.canRead(player, t)) {
					continue;
				}
				try {
					vault.readNote(t.id()).filter(r -> !r.note().encrypted() && r.body().length() <= NoteTemplates.MAX_CHARS)
							.ifPresent(r -> byName.put(name, new Entry(name.length() > 200 ? name.substring(0, 200) : name, r.body())));
				} catch (IOException e) {
					MemoryPalace.LOGGER.warn("Could not read template {}", t.relativePath(), e);
				}
			}
		}
		return new ArrayList<>(byName.values());
	}
}
