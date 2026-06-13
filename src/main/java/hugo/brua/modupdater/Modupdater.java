package hugo.brua.modupdater;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Modupdater implements ModInitializer {
	public static final String MOD_ID = "modupdater";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("[modupdater] Hello Fabric world!");
	}
}
