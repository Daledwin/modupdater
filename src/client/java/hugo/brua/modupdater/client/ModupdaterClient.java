package hugo.brua.modupdater.client;

import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class ModupdaterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 0) A chaque nouvelle connexion, repartir d'un etat propre. On clear en phase LOGIN (avant la
		//    config) : ainsi meme un serveur vanilla qui kicke tot en configuration a deja efface l'etat
		//    du serveur precedent, sans jamais effacer le manifeste de la connexion courante (recu plus
		//    tard, en phase configuration).
		ClientLoginConnectionEvents.INIT.register((handler, client) -> ClientManifestState.clear());

		// 1) Reception du manifeste en phase configuration (avant toute deconnexion).
		ClientConfigurationNetworking.registerGlobalReceiver(ManifestPayload.TYPE,
				(payload, context) -> ClientManifestState.onReceive(payload));

		// 2) Bouton d'install quand des mods manquent, sur deux ecrans :
		//    - DisconnectedScreen : le serveur a refuse la connexion (mod bloquant) ;
		//    - PauseScreen (Echap) : on a REJOINT le serveur mais des mods manquent quand meme (mod non
		//      bloquant pour la connexion) -> sinon le joueur n'aurait aucun point d'entree.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!ClientManifestState.hasWork()) {
				return;
			}
			if (screen instanceof DisconnectedScreen) {
				Screens.getButtons(screen).add(Button.builder(
								Component.literal("Mod Updater : installer les mods manquants"),
								btn -> client.setScreen(new ModSyncScreen(screen)))
						.bounds(scaledWidth / 2 - 110, 6, 220, 20).build());
			} else if (screen instanceof PauseScreen) {
				int n = ClientManifestState.problems().size();
				Screens.getButtons(screen).add(Button.builder(
								Component.literal("Mod Updater : " + n + " mod(s) manquant(s)"),
								btn -> client.setScreen(new ModSyncScreen(screen)))
						.bounds(scaledWidth / 2 - 100, scaledHeight - 30, 200, 20).build());
			}
		});

		// 2b) A la connexion REUSSIE, si des mods manquent : message chat (le joueur a rejoint sans kick,
		//     il faut l'avertir sinon il ne verrait rien).
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (!ClientManifestState.hasWork()) {
				return;
			}
			int n = ClientManifestState.problems().size();
			client.execute(() -> client.gui.getChat().addMessage(
					Component.literal("[Mod Updater] " + n + " mod(s) manquant(s) — Echap puis « Mod Updater » pour installer.")
							.withStyle(ChatFormatting.GOLD)));
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
														new ModEntry("sodium", "0.5.8", "sodium-0.5.8.jar", "client", ""),
														new ModEntry("lithium", "0.13.0", "lithium-0.13.0.jar", "both", ""))));
										client.setScreen(new ModSyncScreen(screen));
									})
							.bounds(scaledWidth / 2 - 110, 6, 220, 20).build();
					Screens.getButtons(screen).add(devButton);
				}
			});
		}
	}
}
