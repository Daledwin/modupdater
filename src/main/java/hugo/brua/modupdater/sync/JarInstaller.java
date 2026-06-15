package hugo.brua.modupdater.sync;

import hugo.brua.modupdater.Modupdater;
import hugo.brua.modupdater.network.ModEntry;
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
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;

/**
 * Telechargement et installation securises d'un jar de mod, partages entre le client (GUI) et le
 * serveur (self-provisioning). Mode strict : source {@code https} obligatoire (sauf localhost),
 * empreinte SHA-256 obligatoire et verifiee avant installation, anti path-traversal, et suppression
 * de l'ancien jar (autre nom de fichier) lors d'une mise a jour pour eviter un duplicate mod-id.
 */
public final class JarInstaller {
	private JarInstaller() {
	}

	public static HttpClient newClient() {
		return HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/** Refuse une source non-https (sauf localhost) : evite le MITM sur le telechargement de code. */
	public static void requireSafeSource(URI base) {
		String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
		String host = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
		boolean localhost = host.equals("localhost") || host.equals("127.0.0.1")
				|| host.equals("::1") || host.equals("[::1]");
		if (scheme.equals("https") || (scheme.equals("http") && localhost)) {
			return;
		}
		throw new IllegalArgumentException(
				"source non-https refusee (http autorise seulement pour localhost) : " + base);
	}

	/** Mode strict : exige une empreinte pour chaque entree (refus du lot AVANT tout telechargement). */
	public static void requireAllHashed(List<ModEntry> entries) {
		for (ModEntry e : entries) {
			if (e.sha256() == null || e.sha256().isBlank()) {
				throw new IllegalArgumentException("sha256 manquant pour " + e.file()
						+ " — ajoute l'empreinte au manifeste (mode strict)");
			}
		}
	}

	/**
	 * Telecharge {@code entry.file()} depuis {@code base}, verifie l'empreinte, installe dans
	 * {@code modsDir}, puis supprime l'ancien jar du meme mod-id s'il portait un autre nom de fichier.
	 * Les jars verrouilles non supprimables sont ajoutes a {@code warnings} (non fatal).
	 */
	public static void installOne(HttpClient client, URI base, ModEntry entry, Path modsDir, List<String> warnings)
			throws IOException, InterruptedException {
		String file = entry.file();
		if (!isSafeFileName(file)) {
			throw new IllegalArgumentException("nom de fichier refuse : " + file);
		}
		if (entry.sha256() == null || entry.sha256().isBlank()) {
			throw new IllegalArgumentException("sha256 manquant pour " + file + " (mode strict)");
		}
		Files.createDirectories(modsDir);
		URI url = base.resolve(file);
		Path part = modsDir.resolve(file + ".part");
		Path target = modsDir.resolve(file);
		boolean moved = false;
		try {
			HttpRequest req = HttpRequest.newBuilder(url).GET().timeout(Duration.ofMinutes(5)).build();
			HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(part));
			if (resp.statusCode() != 200) {
				throw new IOException("HTTP " + resp.statusCode() + " sur " + url);
			}
			verifyIntegrity(part, entry.sha256(), file);
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
			moved = true;
		} finally {
			// Ne jamais laisser trainer un .part (download interrompu, sha invalide, etc.).
			if (!moved) {
				try {
					Files.deleteIfExists(part);
				} catch (IOException ignored) {
					// rien : nettoyage best-effort
				}
			}
		}
		removeSupersededJar(entry.id(), target, modsDir, warnings);
		Modupdater.LOGGER.info("[modupdater] installe : {}", file);
	}

	/** Message court et lisible (les ConnectException ont souvent un getMessage() null). */
	public static String friendlyReason(Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		if (root instanceof UnresolvedAddressException) {
			return "hote introuvable (verifie l'URL)";
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

	private static void removeSupersededJar(String id, Path newTarget, Path modsDir, List<String> warnings) {
		ModContainer mc = FabricLoader.getInstance().getModContainer(id).orElse(null);
		// getOrigin().getPaths() leve UnsupportedOperationException pour NESTED (JiJ) / UNKNOWN.
		if (mc == null || mc.getOrigin().getKind() != ModOrigin.Kind.PATH) {
			return;
		}
		Path modsAbs = realOrNormalize(modsDir);
		Path targetAbs = realOrNormalize(newTarget);
		for (Path old : mc.getOrigin().getPaths()) {
			Path oldAbs = realOrNormalize(old);
			boolean inMods = oldAbs.getParent() != null && oldAbs.getParent().equals(modsAbs);
			if (!inMods || !oldAbs.toString().toLowerCase(Locale.ROOT).endsWith(".jar") || oldAbs.equals(targetAbs)) {
				continue;
			}
			try {
				Files.deleteIfExists(oldAbs);
				Modupdater.LOGGER.info("[modupdater] ancien jar supprime : {}", oldAbs.getFileName());
			} catch (IOException e) {
				warnings.add(oldAbs.getFileName().toString());
				Modupdater.LOGGER.warn("[modupdater] impossible de supprimer l'ancien jar {} : {}",
						oldAbs.getFileName(), e.toString());
			}
		}
	}

	private static Path realOrNormalize(Path p) {
		try {
			return p.toRealPath();
		} catch (IOException e) {
			return p.toAbsolutePath().normalize();
		}
	}

	private static boolean isSafeFileName(String f) {
		return f != null && !f.isBlank() && f.endsWith(".jar")
				&& !f.contains("/") && !f.contains("\\") && !f.contains("..");
	}
}
