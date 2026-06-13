package hugo.brua.modupdater.network;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Payload serveur -> client envoye en phase <em>configuration</em> (avant toute deconnexion) :
 * le manifeste des mods requis et l'URL source ou les telecharger. Le comparatif (quels mods
 * manquent) est fait cote client, qui connait ses propres mods via {@code FabricLoader}.
 */
public record ManifestPayload(String sourceUrl, List<ModEntry> mods) implements CustomPacketPayload {
	// Piege 1.21.11 : ne PAS utiliser createType("...") (interprete l'arg comme un chemin sous le
	// namespace "minecraft" -> IdentifierException). Construire le Type avec un Identifier complet.
	public static final Type<ManifestPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath("modupdater", "manifest"));

	public static final StreamCodec<ByteBuf, ManifestPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, ManifestPayload::sourceUrl,
			ModEntry.STREAM_CODEC.apply(ByteBufCodecs.list()), ManifestPayload::mods,
			ManifestPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
