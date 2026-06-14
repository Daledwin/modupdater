package hugo.brua.modupdater.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Cote serveur (dedie uniquement) : construit le manifeste au demarrage et le pousse au client en
 * phase <em>configuration</em> (avant toute deconnexion). Source du manifeste, selon
 * {@code config/modupdater.json} :
 * <ul>
 *   <li>{@code autoFromServerMods=true} : derive automatiquement les entrees a partir des jars
 *       reellement charges dans {@code mods/} (id, version, nom de fichier, sha256 calcule), en
 *       excluant les mods {@code environment=SERVER}, l'eventuelle liste {@code exclude}, et
 *       modupdater lui-meme. L'admin n'a qu'a uploader les memes jars sur {@code sourceUrl}.</li>
 *   <li>{@code mods[]} : entrees explicites, fusionnees par-dessus l'auto (override par id) — pour
 *       les mods <em>client-only</em> que le serveur ne fait pas tourner.</li>
 * </ul>
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

	/** Construit le manifeste (auto + explicite). Cree un gabarit si absent. Null si rien d'exploitable. */
	private static ManifestPayload load() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				writeTemplate();
				return null;
			}
			Dto dto = GSON.fromJson(Files.readString(CONFIG_PATH), Dto.class);
			if (dto == null || dto.sourceUrl == null || dto.sourceUrl.isBlank()) {
				return null;
			}

			Set<String> exclude = new HashSet<>();
			if (dto.exclude != null) {
				for (String e : dto.exclude) {
					if (e != null && !e.isBlank()) {
						exclude.add(e.trim());
					}
				}
			}

			// id -> entree ; ordre auto puis explicite, l'explicite override l'auto pour un meme id.
			LinkedHashMap<String, ModEntry> byId = new LinkedHashMap<>();
			if (dto.autoFromServerMods) {
				for (ModEntry e : autoEntries(exclude)) {
					byId.put(e.id(), e);
				}
				Modupdater.LOGGER.info("[modupdater] auto : {} mod(s) derive(s) du dossier mods/ du serveur.",
						byId.size());
			}
			if (dto.mods != null) {
				for (ModDto m : dto.mods) {
					ModEntry e = fromDto(m);
					if (e != null) {
						byId.put(e.id(), e);
					}
				}
			}

			if (byId.isEmpty()) {
				return null;
			}
			return new ManifestPayload(dto.sourceUrl, List.copyOf(byId.values()));
		} catch (Exception e) {
			// Tout echec ici annule TOUT le manifeste (aucune synchro) -> niveau error pour la visibilite.
			Modupdater.LOGGER.error("[modupdater] manifeste non construit ({}) : {}", CONFIG_PATH, e.toString());
			return null;
		}
	}

	/**
	 * Derive les entrees a partir des jars autonomes du dossier {@code mods/} du serveur. Exclut les
	 * mods {@code environment=SERVER}, la liste {@code exclude}, modupdater lui-meme, et tout ce qui
	 * n'est pas un jar directement dans {@code mods/} (built-ins, sous-modules JiJ).
	 */
	private static List<ModEntry> autoEntries(Set<String> exclude) {
		List<ModEntry> out = new ArrayList<>();
		Path modsReal = realOrNull(FabricLoader.getInstance().getGameDir().resolve("mods"));
		if (modsReal == null) {
			return out;
		}
		for (ModContainer mc : FabricLoader.getInstance().getAllMods()) {
			ModMetadata md = mc.getMetadata();
			String id = md.getId();
			boolean excluded = exclude.contains(id) || md.getProvides().stream().anyMatch(exclude::contains);
			if (id.equals(Modupdater.MOD_ID) || excluded) {
				continue; // le client a deja modupdater ; respect de la liste d'exclusion (id ou alias provides)
			}
			if (md.getEnvironment() == ModEnvironment.SERVER) {
				continue; // inutile au client
			}
			if (mc.getOrigin().getKind() != ModOrigin.Kind.PATH) {
				continue; // built-in / JiJ : pas un jar autonome de mods/
			}
			Path jar = jarInMods(mc, modsReal);
			if (jar == null) {
				continue; // charge hors de mods/ (classpath dev, autre dossier)
			}
			String sha = sha256Hex(jar);
			if (sha == null) {
				// Jar illisible au demarrage : on ne peut pas l'annoncer sans empreinte -> il ne sera PAS
				// synchronise ce boot-ci (visible dans les logs ; relancer le serveur si transitoire).
				Modupdater.LOGGER.error("[modupdater] {} NON synchronise (sha256 illisible).", id);
				continue;
			}
			String side = md.getEnvironment() == ModEnvironment.CLIENT ? "client" : "both";
			out.add(new ModEntry(id, md.getVersion().getFriendlyString(), jar.getFileName().toString(), side, sha));
		}
		return out;
	}

	/** Le premier chemin d'origine du mod qui est un .jar directement dans {@code mods/}, sinon null. */
	private static Path jarInMods(ModContainer mc, Path modsReal) {
		for (Path p : mc.getOrigin().getPaths()) {
			Path real = realOrNull(p);
			if (real == null || real.getFileName() == null || real.getParent() == null) {
				continue;
			}
			if (real.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
					&& real.getParent().equals(modsReal)) {
				return real;
			}
		}
		return null;
	}

	private static ModEntry fromDto(ModDto m) {
		if (m == null || m.id == null || m.file == null) {
			return null;
		}
		String side = (m.side == null || m.side.isBlank()) ? "both" : m.side;
		String sha256 = m.sha256 == null ? "" : m.sha256.trim().toLowerCase(Locale.ROOT);
		return new ModEntry(m.id, m.version == null ? "" : m.version, m.file, side, sha256);
	}

	private static Path realOrNull(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException e) {
			return null;
		}
	}

	private static String sha256Hex(Path f) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[8192];
			try (InputStream in = Files.newInputStream(f)) {
				int n;
				while ((n = in.read(buf)) != -1) {
					md.update(buf, 0, n);
				}
			}
			StringBuilder sb = new StringBuilder(64);
			for (byte b : md.digest()) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (Exception e) {
			Modupdater.LOGGER.warn("[modupdater] sha256 impossible pour {} : {}", f, e.toString());
			return null;
		}
	}

	private static void writeTemplate() {
		try {
			Dto dto = new Dto();
			dto.sourceUrl = "https://exemple.invalid/mods";
			dto.autoFromServerMods = true;
			dto.exclude = new ArrayList<>(List.of("fabric-api"));
			dto.mods = new ArrayList<>();
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, GSON.toJson(dto));
			Modupdater.LOGGER.info("[modupdater] gabarit de config cree : {} (edite sourceUrl puis relance).",
					CONFIG_PATH);
		} catch (IOException e) {
			Modupdater.LOGGER.warn("[modupdater] impossible d'ecrire le gabarit de config : {}", e.toString());
		}
	}

	/** DTO Gson (champs mutables, peuples par reflexion — pas de dependance au support des records). */
	private static final class Dto {
		String sourceUrl;
		boolean autoFromServerMods;
		List<String> exclude;
		List<ModDto> mods;
	}

	private static final class ModDto {
		String id;
		String version;
		String file;
		String side;
		String sha256;
	}
}
