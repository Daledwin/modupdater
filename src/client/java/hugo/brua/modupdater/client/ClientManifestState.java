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
			if (!e.version().isBlank() && !installed.equals(e.version())) {
				found.add(new Problem(e, Kind.OUTDATED, installed));
			}
		}
		sourceUrl = payload.sourceUrl();
		problems = List.copyOf(found);
		Modupdater.LOGGER.info("[modupdater] manifeste recu : {} mods, {} a corriger.",
				payload.mods().size(), found.size());
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
