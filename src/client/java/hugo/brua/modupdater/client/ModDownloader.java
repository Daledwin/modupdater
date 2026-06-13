package hugo.brua.modupdater.client;

import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.client.ClientManifestState.Problem;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Telecharge les jars manquants depuis l'URL source vers le dossier {@code mods/}, sur un thread
 * de fond (ne bloque pas le rendu). Les mods ne prennent effet qu'au prochain demarrage (Fabric
 * ne peut pas charger un mod a chaud) -> l'ecran propose ensuite de quitter le jeu.
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
			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(15))
					.followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
			Files.createDirectories(modsDir);
			URI base = URI.create(sourceUrl.endsWith("/") ? sourceUrl : sourceUrl + "/");

			int i = 0;
			for (Problem p : problems) {
				i++;
				String file = p.entry().file();
				if (!isSafeFileName(file)) {
					throw new IllegalArgumentException("nom de fichier refuse : " + file);
				}
				message = "(" + i + "/" + problems.size() + ") " + file;
				URI url = base.resolve(file);
				Path part = modsDir.resolve(file + ".part");
				HttpRequest req = HttpRequest.newBuilder(url).GET().timeout(Duration.ofMinutes(5)).build();
				HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(part));
				if (resp.statusCode() != 200) {
					Files.deleteIfExists(part);
					throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
				}
				Files.move(part, modsDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
				Modupdater.LOGGER.info("[modupdater] telecharge : {}", file);
			}
			message = problems.size() + " mod(s) installes. Redemarrage requis.";
			state = State.DONE;
		} catch (IOException | InterruptedException | IllegalArgumentException e) {
			// Echec operationnel attendu (hote injoignable, coupure, HTTP, config) : pas de stack trace.
			String reason = friendlyReason(e);
			Modupdater.LOGGER.warn("[modupdater] echec du telechargement : {}", reason);
			message = "Echec : " + reason;
			state = State.ERROR;
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		} catch (Exception e) {
			// Erreur inattendue (bug) : on garde la trace pour debug.
			Modupdater.LOGGER.error("[modupdater] echec inattendu du telechargement", e);
			message = "Echec : " + friendlyReason(e);
			state = State.ERROR;
		}
	}

	/** Message court et lisible pour la GUI/le log (les ConnectException ont souvent un getMessage() null). */
	private static String friendlyReason(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		if (root instanceof UnresolvedAddressException) {
			return "hote introuvable (verifie l'URL source)";
		}
		String m = t.getMessage();
		if (m == null || m.isBlank()) {
			m = root.getMessage();
		}
		if (m == null || m.isBlank()) {
			m = root.getClass().getSimpleName();
		}
		return m;
	}

	/** Empeche le path traversal : seul un nom de .jar simple est accepte. */
	private static boolean isSafeFileName(String f) {
		return f != null && !f.isBlank() && f.endsWith(".jar")
				&& !f.contains("/") && !f.contains("\\") && !f.contains("..");
	}
}
