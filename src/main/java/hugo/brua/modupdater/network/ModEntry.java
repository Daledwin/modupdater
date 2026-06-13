package hugo.brua.modupdater.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Une entree du manifeste : un mod requis cote client.
 *
 * @param id      l'id Fabric du mod (ex: "sodium")
 * @param version la version requise (ex: "0.5.8")
 * @param file    le nom du fichier .jar a telecharger depuis l'URL source (ex: "sodium-0.5.8.jar")
 * @param side    "client" (purement client) ou "both" ; informatif, defaut "both"
 */
public record ModEntry(String id, String version, String file, String side) {
	public static final StreamCodec<ByteBuf, ModEntry> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, ModEntry::id,
			ByteBufCodecs.STRING_UTF8, ModEntry::version,
			ByteBufCodecs.STRING_UTF8, ModEntry::file,
			ByteBufCodecs.STRING_UTF8, ModEntry::side,
			ModEntry::new);
}
