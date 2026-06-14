package hugo.brua.modupdater.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Une entree du manifeste : un mod requis cote client.
 *
 * @param id      l'id Fabric du mod (ex: "sodium")
 * @param version la version requise (ex: "0.5.8") ; comparee a la version installee en ignorant les
 *                metadonnees de build SemVer ({@code +mc1.21.11})
 * @param file    le nom du fichier .jar a telecharger depuis l'URL source (ex: "sodium-0.5.8.jar")
 * @param side    "client" (purement client) ou "both" ; informatif, defaut "both"
 * @param sha256  empreinte SHA-256 hex (minuscules) du .jar attendu ; OBLIGATOIRE (mode strict) :
 *                un mod sans empreinte est refuse au telechargement. Verifiee cote client avant install.
 */
public record ModEntry(String id, String version, String file, String side, String sha256) {
	public static final StreamCodec<ByteBuf, ModEntry> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, ModEntry::id,
			ByteBufCodecs.STRING_UTF8, ModEntry::version,
			ByteBufCodecs.STRING_UTF8, ModEntry::file,
			ByteBufCodecs.STRING_UTF8, ModEntry::side,
			ByteBufCodecs.STRING_UTF8, ModEntry::sha256,
			ModEntry::new);
}
