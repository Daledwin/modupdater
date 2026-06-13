package hugo.brua.modupdater.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Cote serveur : lit {@code config/modupdater.json} (manifeste explicite ecrit par l'admin) et
 * pousse le {@link ManifestPayload} au client en phase <em>configuration</em>, avant toute
 * deconnexion. Si le client n'a pas modupdater, {@code canSend} est faux et on ne fait rien
 * (connexion vanilla normale).
 */
public final class ServerManifest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH =
			FabricLoader.getInstance().getConfigDir().resolve("modupdater.json");

	/** Manifeste charge une fois au demarrage du serveur (null = rien a synchroniser). */
	private static volatile ManifestPayload cached;

	private ServerManifest() {
	}

	public static void register() {
		// Lit la config (et cree le gabarit si absent) AU DEMARRAGE du serveur, pas a la 1re connexion :
		// ainsi config/modupdater.json existe des le 1er boot, sans qu'un client ait besoin de se connecter.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> cached = load());

		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			ManifestPayload payload = cached;
			if (payload == null || payload.mods().isEmpty()) {
				return; // rien a synchroniser
			}
			if (ServerConfigurationNetworking.canSend(handler, ManifestPayload.TYPE)) {
				ServerConfigurationNetworking.send(handler, payload);
				Modupdater.LOGGER.info("[modupdater] manifeste envoye ({} mods) en phase configuration.",
						payload.mods().size());
			}
		});
	}

	/** Lit le manifeste. Cree un gabarit si le fichier est absent. Renvoie null si rien d'exploitable. */
	private static ManifestPayload load() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				writeTemplate();
				return null;
			}
			Dto dto = GSON.fromJson(Files.readString(CONFIG_PATH), Dto.class);
			if (dto == null || dto.sourceUrl == null || dto.sourceUrl.isBlank() || dto.mods == null) {
				return null;
			}
			List<ModEntry> mods = new ArrayList<>();
			for (ModDto m : dto.mods) {
				if (m == null || m.id == null || m.file == null) {
					continue;
				}
				String side = (m.side == null || m.side.isBlank()) ? "both" : m.side;
				mods.add(new ModEntry(m.id, m.version == null ? "" : m.version, m.file, side));
			}
			return new ManifestPayload(dto.sourceUrl, mods);
		} catch (Exception e) {
			Modupdater.LOGGER.warn("[modupdater] config illisible ({}) : {}", CONFIG_PATH, e.toString());
			return null;
		}
	}

	private static void writeTemplate() {
		try {
			Dto dto = new Dto();
			dto.sourceUrl = "https://exemple.invalid/mods";
			dto.mods = new ArrayList<>();
			ModDto sample = new ModDto();
			sample.id = "sodium";
			sample.version = "0.5.8";
			sample.file = "sodium-0.5.8.jar";
			sample.side = "client";
			dto.mods.add(sample);
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, GSON.toJson(dto));
			Modupdater.LOGGER.info("[modupdater] gabarit de config cree : {} (a editer puis relancer).", CONFIG_PATH);
		} catch (IOException e) {
			Modupdater.LOGGER.warn("[modupdater] impossible d'ecrire le gabarit de config : {}", e.toString());
		}
	}

	/** DTO Gson (champs mutables, peuples par reflexion — pas de dependance au support des records). */
	private static final class Dto {
		String sourceUrl;
		List<ModDto> mods;
	}

	private static final class ModDto {
		String id;
		String version;
		String file;
		String side;
	}
}
