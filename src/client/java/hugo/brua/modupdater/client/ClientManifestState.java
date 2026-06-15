package hugo.brua.modupdater.client;

import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
import hugo.brua.modupdater.sync.Versions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Etat client : memorise le dernier manifeste recu du serveur (en phase configuration) et le
 * resultat du comparatif avec les mods reellement charges. Lu ensuite par l'ecran de deconnexion
 * pour proposer le telechargement des manquants.
 */
public final class ClientManifestState {
	/** Nature du probleme pour un mod du manifeste. */
	public enum Kind {
		MISSING, OUTDATED
	}

	/** Un mod du manifeste qui n'est pas (correctement) installe cote client. */
	public record Problem(ModEntry entry, Kind kind, String installedVersion) {
	}

	private static volatile String sourceUrl = "";
	private static volatile List<Problem> problems = List.of();

	private ClientManifestState() {
	}

	/** Appele a la reception du payload (thread reseau) : recalcule le diff. */
	public static void onReceive(ManifestPayload payload) {
		List<Problem> found = new ArrayList<>();
		for (ModEntry e : payload.mods()) {
			Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(e.id());
			// getModContainer resout les alias 'provides' -> exiger l'id PRIMAIRE, sinon le mod n'est
			// present que comme alias d'un autre et doit etre considere manquant.
			if (mc.isEmpty() || !mc.get().getMetadata().getId().equals(e.id())) {
				found.add(new Problem(e, Kind.MISSING, ""));
				continue;
			}
			String installed = mc.get().getMetadata().getVersion().getFriendlyString();
			if (!e.version().isBlank() && !Versions.sameVersion(installed, e.version())) {
				found.add(new Problem(e, Kind.OUTDATED, installed));
			}
		}
		sourceUrl = payload.sourceUrl();
		problems = List.copyOf(found);
		// Nouveau manifeste = nouveau contexte : repartir d'un etat de telechargement propre
		// (sinon un DONE/ERROR d'une connexion precedente grise le bouton ou affiche un message perime).
		ModDownloader.reset();
		Modupdater.LOGGER.info("[modupdater] manifeste recu : {} mods, {} a corriger.",
				payload.mods().size(), found.size());
	}

	/** Vide l'etat (a chaque nouvelle connexion) pour ne pas trainer le manifeste d'un serveur precedent. */
	public static void clear() {
		sourceUrl = "";
		problems = List.of();
		ModDownloader.reset();
	}

	public static boolean hasWork() {
		return !problems.isEmpty();
	}

	public static List<Problem> problems() {
		return problems;
	}

	public static String sourceUrl() {
		return sourceUrl;
	}
}
