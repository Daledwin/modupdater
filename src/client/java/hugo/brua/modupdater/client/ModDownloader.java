package hugo.brua.modupdater.client;

import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.client.ClientManifestState.Problem;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Telecharge les jars manquants depuis l'URL source vers le dossier {@code mods/}, sur un thread
 * de fond (ne bloque pas le rendu). Les mods ne prennent effet qu'au prochain demarrage (Fabric
 * ne peut pas charger un mod a chaud) -> l'ecran propose ensuite de quitter le jeu.
 *
 * <p>Securite (mode strict) : refuse les sources non-{@code https} (sauf localhost), EXIGE une
 * empreinte SHA-256 pour chaque mod du manifeste, et verifie cette empreinte avant d'installer le
 * jar dans {@code mods/}. Un mod sans empreinte fait echouer le lot (jamais de code non verifie).
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
			requireSafeSource(base);
			requireAllHashed(problems);

			HttpClient client = HttpClient.newBuilder()
					.connectTimeout(Duration.ofSeconds(15))
					.followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
			Files.createDirectories(modsDir);

			List<String> warnings = new ArrayList<>();
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

				// Verification d'integrite AVANT d'installer le jar dans mods/.
				verifyIntegrity(part, p.entry().sha256(), file);

				Path target = modsDir.resolve(file);
				Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
				Modupdater.LOGGER.info("[modupdater] telecharge : {}", file);

				// Mise a jour (OUTDATED) : supprimer l'ancien jar du meme mod s'il porte un autre nom de
				// fichier, sinon Fabric verra 2 jars avec le meme mod-id au prochain boot et refusera de
				// demarrer (duplicate mod id).
				removeSupersededJar(p.entry().id(), target, modsDir, warnings);
			}

			String done = problems.size() + " mod(s) installes. Redemarrage requis.";
			if (!warnings.isEmpty()) {
				done += " (a nettoyer a la main : " + String.join(", ", warnings) + ")";
			}
			message = done;
			state = State.DONE;
		} catch (IOException | InterruptedException | IllegalArgumentException e) {
			// Echec operationnel attendu (hote injoignable, coupure, HTTP, integrite, config) : pas de trace.
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

	/** Refuse une source non-https (sauf localhost) : evite le MITM sur le telechargement de code. */
	private static void requireSafeSource(URI base) {
		String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
		String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
		// URI.getHost() renvoie l'IPv6 entre crochets ("[::1]") -> accepter les deux formes.
		boolean localhost = host.equals("localhost") || host.equals("127.0.0.1")
				|| host.equals("::1") || host.equals("[::1]");
		if (scheme.equals("https") || (scheme.equals("http") && localhost)) {
			return;
		}
		throw new IllegalArgumentException(
				"source non-https refusee (http autorise seulement pour localhost) : " + base);
	}

	/** Mode strict : exige une empreinte pour chaque mod AVANT tout telechargement (refus du lot sinon). */
	private static void requireAllHashed(List<Problem> problems) {
		for (Problem p : problems) {
			String sha = p.entry().sha256();
			if (sha == null || sha.isBlank()) {
				throw new IllegalArgumentException("sha256 manquant pour " + p.entry().file()
						+ " — ajoute l'empreinte au manifeste (mode strict)");
			}
		}
	}

	/** Verifie le SHA-256 du fichier telecharge avant installation. Vide ou mismatch = abort. */
	private static void verifyIntegrity(Path part, String expectedHex, String file) throws IOException {
		if (expectedHex == null || expectedHex.isBlank()) {
			Files.deleteIfExists(part);
			throw new IOException(file + " : sha256 manquant (mode strict)");
		}
		String actual = sha256Hex(part);
		if (!actual.equalsIgnoreCase(expectedHex.trim())) {
			Files.deleteIfExists(part);
			throw new IOException("sha256 invalide pour " + file + " (attendu " + expectedHex.trim()
					+ ", obtenu " + actual + ")");
		}
	}

	private static String sha256Hex(Path f) throws IOException {
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
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 indisponible", e); // jamais sur un JDK standard
		}
	}

	/**
	 * Supprime l'ancien jar du mod {@code id} s'il existe sous un autre nom de fichier dans {@code mods/}.
	 * Sur Windows le jar charge peut etre verrouille -> on n'echoue pas, on signale (nettoyage manuel).
	 */
	private static void removeSupersededJar(String id, Path newTarget, Path modsDir, List<String> warnings) {
		Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer(id);
		// getOrigin().getPaths() leve UnsupportedOperationException pour les origines NESTED (JiJ) et
		// UNKNOWN -> ne l'appeler que pour Kind.PATH. Un mod JiJ n'a de toute facon pas de jar autonome
		// dans mods/ a supprimer (limite connue : un doublon JiJ ne peut pas etre resolu ici).
		if (mc.isEmpty() || mc.get().getOrigin().getKind() != ModOrigin.Kind.PATH) {
			return;
		}
		Path modsAbs = realOrNormalize(modsDir);
		Path targetAbs = realOrNormalize(newTarget);
		for (Path old : mc.get().getOrigin().getPaths()) {
			Path oldAbs = realOrNormalize(old);
			boolean inMods = oldAbs.getParent() != null && oldAbs.getParent().equals(modsAbs);
			if (!inMods || !oldAbs.toString().toLowerCase(Locale.ROOT).endsWith(".jar") || oldAbs.equals(targetAbs)) {
				continue; // pas un jar de mods/, ou deja le fichier qu'on vient d'ecrire
			}
			try {
				Files.deleteIfExists(oldAbs);
				Modupdater.LOGGER.info("[modupdater] ancien jar supprime : {}", oldAbs.getFileName());
			} catch (IOException e) {
				// Typiquement Windows : jar charge et verrouille pour la duree de la JVM.
				warnings.add(oldAbs.getFileName().toString());
				Modupdater.LOGGER.warn("[modupdater] impossible de supprimer l'ancien jar {} : {}",
						oldAbs.getFileName(), e.toString());
			}
		}
	}

	/** Chemin reel (resout les symlinks pour matcher les chemins normalises par Fabric) ; repli si absent. */
	private static Path realOrNormalize(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException e) {
			return p.toAbsolutePath().normalize();
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
