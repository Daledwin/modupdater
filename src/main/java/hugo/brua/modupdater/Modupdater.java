package hugo.brua.modupdater;

import hugo.brua.modupdater.network.ModupdaterNet;
import hugo.brua.modupdater.server.ServerManifest;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Modupdater implements ModInitializer {
	public static final String MOD_ID = "modupdater";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModupdaterNet.registerPayloads();
		ServerManifest.register();
		LOGGER.info("[modupdater] pret (payloads + envoi manifeste en phase configuration).");
	}
}
