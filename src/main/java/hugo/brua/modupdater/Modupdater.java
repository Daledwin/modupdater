package hugo.brua.modupdater;

import hugo.brua.modupdater.network.ModupdaterNet;
import hugo.brua.modupdater.server.ServerManifest;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Modupdater implements ModInitializer {
	public static final String MOD_ID = "modupdater";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Le codec doit etre enregistre des deux cotes (le client doit savoir decoder).
		ModupdaterNet.registerPayloads();

		// L'envoi de manifeste ne concerne QUE le serveur dedie. On le gate sur EnvType.SERVER pour ne
		// PAS l'activer sur le serveur integre d'un client (solo/LAN), qui partage le dossier config du
		// joueur et s'auto-enverrait un manifeste -> pollution de l'etat client.
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
			ServerManifest.register();
			LOGGER.info("[modupdater] serveur dedie : envoi du manifeste en phase configuration actif.");
		} else {
			// Client (y compris serveur integre solo/LAN) : pas d'envoi de manifeste. La reception est
			// activee separement dans l'entrypoint client (ModupdaterClient).
			LOGGER.info("[modupdater] role client : envoi de manifeste desactive (serveur dedie uniquement).");
		}
	}
}
