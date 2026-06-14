package hugo.brua.modupdater.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** Enregistrement des payloads reseau, appele cote commun (serveur ET client). */
public final class ModupdaterNet {
	private ModupdaterNet() {
	}

	/** Doit etre appele dans l'init commun pour que les deux cotes connaissent le codec. */
	public static void registerPayloads() {
		PayloadTypeRegistry.configurationS2C().register(ManifestPayload.TYPE, ManifestPayload.STREAM_CODEC);
	}
}
