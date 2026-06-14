package hugo.brua.modupdater.client;

import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.network.ManifestPayload;
import hugo.brua.modupdater.network.ModEntry;
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
			if (mc.isEmpty()) {
				found.add(new Problem(e, Kind.MISSING, ""));
				continue;
			}
			String installed = mc.get().getMetadata().getVersion().getFriendlyString();
			if (!e.version().isBlank() && !sameVersion(installed, e.version())) {
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

	/**
	 * Compare deux versions en ignorant les metadonnees de build SemVer (tout ce qui suit {@code +}).
	 * {@code getFriendlyString()} renvoie typiquement {@code 0.5.8+mc1.21.11} alors que le manifeste
	 * porte {@code 0.5.8} -> sans ce strip, tout mod serait faussement signale OUTDATED en permanence.
	 */
	private static boolean sameVersion(String installed, String required) {
		return core(installed).equals(core(required));
	}

	private static String core(String v) {
		int i = v.indexOf('+');
		return i < 0 ? v : v.substring(0, i);
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
