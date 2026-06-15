package hugo.brua.modupdater.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
import hugo.brua.modupdater.sync.JarInstaller;
import hugo.brua.modupdater.sync.Versions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Cote serveur dedie. {@code config/modupdater.json} contient une {@code repoUrl} : un depot HTTP
 * hebergeant un {@code index.json} (liste complete des mods : id, version, file, side, sha256) et
 * les jars. Au demarrage, modupdater :
 * <ol>
 *   <li>recupere l'index ;</li>
 *   <li><b>self-provisioning</b> : telecharge dans son {@code mods/} les entrees {@code server}/{@code both}
 *       manquantes ou obsoletes (verif sha256) -> <b>redemarrage requis</b> pour les charger ;</li>
 *   <li>construit le manifeste client ({@code client}/{@code both}) et le pousse aux clients en phase
 *       configuration. Les clients telechargent du meme depot.</li>
 * </ol>
 */
public final class ServerManifest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH =
			FabricLoader.getInstance().getConfigDir().resolve("modupdater.json");

	/** Manifeste client construit une fois au demarrage (null = rien a pousser). */
	private static volatile ManifestPayload cached;

	private ServerManifest() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> cached = bootstrap());

		ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
			ManifestPayload payload = cached;
			if (payload == null || payload.mods().isEmpty()) {
				return;
			}
			if (ServerConfigurationNetworking.canSend(handler, ManifestPayload.TYPE)) {
				ServerConfigurationNetworking.send(handler, payload);
				Modupdater.LOGGER.info("[modupdater] manifeste envoye ({} mods) en phase configuration.",
						payload.mods().size());
			}
		});
	}

	/** Lit la config, recupere l'index, self-provisionne, et renvoie le manifeste client (ou null). */
	private static ManifestPayload bootstrap() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				writeTemplate();
				return null;
			}
			Cfg cfg = GSON.fromJson(Files.readString(CONFIG_PATH), Cfg.class);
			if (cfg == null || cfg.repoUrl == null || cfg.repoUrl.isBlank()) {
				Modupdater.LOGGER.warn("[modupdater] repoUrl absent de {} : rien a synchroniser.", CONFIG_PATH);
				return null;
			}
			URI base = URI.create(cfg.repoUrl.endsWith("/") ? cfg.repoUrl : cfg.repoUrl + "/");
			JarInstaller.requireSafeSource(base);
			HttpClient client = JarInstaller.newClient();

			List<ModEntry> index = dedupById(fetchIndex(client, base, cfg.indexFile));
			Modupdater.LOGGER.info("[modupdater] index recupere : {} mods.", index.size());

			// Self-provision EN TACHE DE FOND : les downloads peuvent etre longs et de toute facon les
			// mods ne se chargent qu'au redemarrage -> ne pas bloquer le boot du serveur (le socket n'est
			// pas encore ouvert a ce stade). Le manifeste client, lui, est construit tout de suite.
			List<ModEntry> serverMods = index.stream().filter(e -> isServerSide(e.side())).toList();
			if (!serverMods.isEmpty()) {
				Thread t = new Thread(() -> provisionServer(client, base, serverMods), "modupdater-provision");
				t.setDaemon(true);
				t.start();
			}

			List<ModEntry> clientMods = index.stream().filter(e -> isClientSide(e.side())).toList();
			if (clientMods.isEmpty()) {
				return null;
			}
			return new ManifestPayload(cfg.repoUrl, List.copyOf(clientMods));
		} catch (Exception e) {
			// N'empeche PAS le serveur de booter ; on logge et on ne pousse rien.
			Modupdater.LOGGER.error("[modupdater] depot non exploitable ({}) : {}", CONFIG_PATH,
					JarInstaller.friendlyReason(e));
			return null;
		}
	}

	/** Telecharge dans mods/ les mods server/both absents ou en mauvaise version (redemarrage requis). */
	private static void provisionServer(HttpClient client, URI base, List<ModEntry> serverMods) {
		Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
		List<String> warnings = new ArrayList<>();
		int installed = 0;
		for (ModEntry e : serverMods) {
			if (presentAndCurrent(e)) {
				continue;
			}
			try {
				JarInstaller.installOne(client, base, e, modsDir, warnings);
				installed++;
			} catch (Exception ex) {
				Modupdater.LOGGER.error("[modupdater] echec install serveur {} : {}", e.file(),
						JarInstaller.friendlyReason(ex));
			}
		}
		if (installed > 0) {
			Modupdater.LOGGER.warn("[modupdater] {} mod(s) serveur installes -> REDEMARRAGE REQUIS pour les charger.",
					installed);
		}
		if (!warnings.isEmpty()) {
			Modupdater.LOGGER.warn("[modupdater] anciens jars a supprimer a la main : {}",
					String.join(", ", warnings));
		}
	}

	/** true si le mod est deja charge avec une version compatible (rien a telecharger). */
	private static boolean presentAndCurrent(ModEntry e) {
		ModContainer mc = FabricLoader.getInstance().getModContainer(e.id()).orElse(null);
		// getModContainer resout les alias 'provides' -> exiger que l'id PRIMAIRE corresponde, sinon
		// l'id n'est present que comme alias d'un autre mod et il faut bien provisionner notre jar.
		if (mc == null || !mc.getMetadata().getId().equals(e.id())) {
			return false;
		}
		String installed = mc.getMetadata().getVersion().getFriendlyString();
		return e.version().isBlank() || Versions.sameVersion(installed, e.version());
	}

	private static List<ModEntry> fetchIndex(HttpClient client, URI base, String indexFile)
			throws IOException, InterruptedException {
		String name = (indexFile == null || indexFile.isBlank()) ? "index.json" : indexFile.trim();
		URI idx = base.resolve(name);
		// Timeout borne : ce GET est synchrone au boot, il ne doit pas faire trainer le demarrage.
		HttpRequest req = HttpRequest.newBuilder(idx).GET().timeout(Duration.ofSeconds(20)).build();
		HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) {
			throw new IOException("HTTP " + resp.statusCode() + " sur " + idx);
		}
		IndexDto dto = GSON.fromJson(resp.body(), IndexDto.class);
		List<ModEntry> out = new ArrayList<>();
		if (dto != null && dto.mods != null) {
			for (EntryDto m : dto.mods) {
				if (m == null || m.id == null || m.file == null) {
					continue;
				}
				String side = (m.side == null || m.side.isBlank()) ? "both" : m.side.trim().toLowerCase(Locale.ROOT);
				if (!isServerSide(side) && !isClientSide(side)) {
					Modupdater.LOGGER.warn("[modupdater] side '{}' inconnu pour {} -> entree ignoree.", side, m.id);
					continue; // typo (ex: 'sever') : ne PAS distribuer par defaut (plus sur)
				}
				String sha = m.sha256 == null ? "" : m.sha256.trim().toLowerCase(Locale.ROOT);
				out.add(new ModEntry(m.id, m.version == null ? "" : m.version, m.file, side, sha));
			}
		}
		return out;
	}

	/** Deduplique par id (garde la 1re occurrence, avertit sur les doublons de l'index). */
	private static List<ModEntry> dedupById(List<ModEntry> entries) {
		java.util.LinkedHashMap<String, ModEntry> byId = new java.util.LinkedHashMap<>();
		for (ModEntry e : entries) {
			if (byId.putIfAbsent(e.id(), e) != null) {
				Modupdater.LOGGER.warn("[modupdater] id '{}' en double dans l'index -> doublon ignore.", e.id());
			}
		}
		return List.copyOf(byId.values());
	}

	private static boolean isServerSide(String side) {
		return side.equals("server") || side.equals("both");
	}

	private static boolean isClientSide(String side) {
		return side.equals("client") || side.equals("both");
	}

	private static void writeTemplate() {
		try {
			Cfg cfg = new Cfg();
			cfg.repoUrl = "https://exemple.invalid/repo";
			cfg.indexFile = "index.json";
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
			Modupdater.LOGGER.info("[modupdater] gabarit de config cree : {} (mets ta repoUrl puis relance).",
					CONFIG_PATH);
		} catch (IOException e) {
			Modupdater.LOGGER.warn("[modupdater] impossible d'ecrire le gabarit de config : {}", e.toString());
		}
	}

	/** Config serveur. */
	private static final class Cfg {
		String repoUrl;
		String indexFile;
	}

	/** index.json : { "mods": [ { id, version, file, side, sha256 }, ... ] }. */
	private static final class IndexDto {
		List<EntryDto> mods;
	}

	private static final class EntryDto {
		String id;
		String version;
		String file;
		String side;
		String sha256;
	}
}
