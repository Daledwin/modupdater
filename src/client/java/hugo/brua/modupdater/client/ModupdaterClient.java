package hugo.brua.modupdater.client;

import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class ModupdaterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 1) Reception du manifeste en phase configuration (avant toute deconnexion).
		ClientConfigurationNetworking.registerGlobalReceiver(ManifestPayload.TYPE,
				(payload, context) -> ClientManifestState.onReceive(payload));

		// 2) Sur l'ecran de deconnexion (refus du serveur), si des mods manquent : bouton d'install.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof DisconnectedScreen && ClientManifestState.hasWork()) {
				Button install = Button.builder(
								Component.literal("Mod Updater : installer les mods manquants"),
								btn -> client.setScreen(new ModSyncScreen(screen)))
						.bounds(scaledWidth / 2 - 110, 6, 220, 20).build();
				Screens.getButtons(screen).add(install);
			}
		});

		// 3) DEV uniquement : bouton sur l'ecran titre qui injecte un faux manifeste et ouvre la GUI,
		//    pour eyeball l'ecran en runClient sans serveur. Absent du jar livre (gate dev).
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
				if (screen instanceof TitleScreen) {
					Button devButton = Button.builder(
									Component.literal("Mod Updater (dev) : ouvrir la GUI"),
									btn -> {
										ClientManifestState.onReceive(new ManifestPayload(
												"https://exemple.invalid/mods",
												List.of(
														new ModEntry("sodium", "0.5.8", "sodium-0.5.8.jar", "client"),
														new ModEntry("lithium", "0.13.0", "lithium-0.13.0.jar", "both"))));
										client.setScreen(new ModSyncScreen(screen));
									})
							.bounds(scaledWidth / 2 - 110, 6, 220, 20).build();
					Screens.getButtons(screen).add(devButton);
				}
			});
		}
	}
}
