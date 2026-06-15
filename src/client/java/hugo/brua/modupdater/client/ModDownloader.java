package hugo.brua.modupdater.client;

import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.client.ClientManifestState.Problem;
import hugo.brua.modupdater.network.ModEntry;
import hugo.brua.modupdater.sync.JarInstaller;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Pilote cote client le telechargement des mods manquants (etat + thread de fond pour la GUI). La
 * logique securisee de telechargement/installation est dans {@link JarInstaller} (partagee avec le
 * serveur). Les mods ne prennent effet qu'au prochain demarrage -> l'ecran propose de quitter le jeu.
 */
public final class ModDownloader {
	public enum State {
		IDLE, RUNNING, DONE, ERROR
	}

	private static volatile State state = State.IDLE;
	private static volatile String message = "";

	private ModDownloader() {
	}

	public static State state() {
		return state;
	}

	public static String message() {
		return message;
	}

	/** Remet l'etat a IDLE (sauf si un telechargement est en cours). Appele a chaque nouveau contexte. */
	public static void reset() {
		if (state != State.RUNNING) {
			state = State.IDLE;
			message = "";
		}
	}

	/** Demarre le telechargement en tache de fond (ignore si deja en cours). */
	public static void start(String sourceUrl, List<Problem> problems) {
		if (state == State.RUNNING || sourceUrl == null || sourceUrl.isBlank() || problems.isEmpty()) {
			return;
		}
		state = State.RUNNING;
		message = "Demarrage...";
		Thread t = new Thread(() -> run(sourceUrl, problems), "modupdater-download");
		t.setDaemon(true);
		t.start();
	}

	private static void run(String sourceUrl, List<Problem> problems) {
		try {
			URI base = URI.create(sourceUrl.endsWith("/") ? sourceUrl : sourceUrl + "/");
			JarInstaller.requireSafeSource(base);
			List<ModEntry> entries = problems.stream().map(Problem::entry).toList();
			JarInstaller.requireAllHashed(entries);

			HttpClient client = JarInstaller.newClient();
			Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
			List<String> warnings = new ArrayList<>();
			for (int i = 0; i < problems.size(); i++) {
				ModEntry e = problems.get(i).entry();
				message = "(" + (i + 1) + "/" + problems.size() + ") " + e.file();
				JarInstaller.installOne(client, base, e, modsDir, warnings);
			}

			String done = problems.size() + " mod(s) installes. Redemarrage requis.";
			if (!warnings.isEmpty()) {
				done += " (a nettoyer a la main : " + String.join(", ", warnings) + ")";
			}
			message = done;
			state = State.DONE;
		} catch (java.io.IOException | InterruptedException | IllegalArgumentException e) {
			String reason = JarInstaller.friendlyReason(e);
			Modupdater.LOGGER.warn("[modupdater] echec du telechargement : {}", reason);
			message = "Echec : " + reason;
			state = State.ERROR;
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		} catch (Exception e) {
			Modupdater.LOGGER.error("[modupdater] echec inattendu du telechargement", e);
			message = "Echec : " + JarInstaller.friendlyReason(e);
			state = State.ERROR;
		}
	}
}
