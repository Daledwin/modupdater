package hugo.brua.modupdater.sync;

/** Comparaison de versions partagee client/serveur. */
public final class Versions {
	private Versions() {
	}

	/**
	 * Egalite en ignorant les metadonnees de build SemVer (tout ce qui suit le premier {@code +}).
	 * {@code getFriendlyString()} renvoie typiquement {@code 0.5.8+mc1.21.11} alors que le manifeste
	 * porte {@code 0.5.8} -> sans ce strip, tout mod serait faussement signale obsolete.
	 */
	public static boolean sameVersion(String installed, String required) {
		return core(installed).equals(core(required));
	}

	private static String core(String v) {
		if (v == null) {
			return "";
		}
		int i = v.indexOf('+');
		return i < 0 ? v : v.substring(0, i);
	}
}
