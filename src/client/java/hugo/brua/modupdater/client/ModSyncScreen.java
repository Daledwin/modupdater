package hugo.brua.modupdater.client;

import hugo.brua.modupdater.client.ClientManifestState.Kind;
import hugo.brua.modupdater.client.ClientManifestState.Problem;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Ecran client listant les mods manquants/obsoletes et permettant de les telecharger depuis l'URL
 * source. Une fois fini : message "redemarrage requis" + bouton pour quitter le jeu proprement.
 */
public class ModSyncScreen extends Screen {
	private static final int MAX_LINES = 8;

	private final Screen parent;
	private Button downloadButton;
	private Button quitButton;

	public ModSyncScreen(Screen parent) {
		super(Component.literal("Mod Updater"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int by = this.height - 52;
		this.downloadButton = this.addRenderableWidget(
				Button.builder(Component.literal("Telecharger les mods manquants"),
								b -> ModDownloader.start(ClientManifestState.sourceUrl(), ClientManifestState.problems()))
						.bounds(cx - 200, by, 200, 20).build());
		this.quitButton = this.addRenderableWidget(
				Button.builder(Component.literal("Quitter le jeu"), b -> this.minecraft.stop())
						.bounds(cx + 4, by, 196, 20).build());
		this.addRenderableWidget(
				Button.builder(Component.literal("Retour"), b -> this.minecraft.setScreen(this.parent))
						.bounds(cx - 100, by + 24, 200, 20).build());
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		// Le fond (et le flou) est deja rendu par le moteur (renderWithTooltipAndSubtitles) AVANT
		// cet appel ; surtout ne pas rappeler renderBackground ici -> "Can only blur once per frame".
		super.render(g, mouseX, mouseY, delta);

		// Couleurs en ARGB avec alpha PLEIN (0xFF...) : en 1.21.11 drawString ne force plus l'opacite,
		// une couleur sans alpha (0x00......) est totalement transparente -> texte invisible.
		g.drawCenteredString(this.font, "Mod Updater — synchronisation", this.width / 2, 16, 0xFFFFFFFF);

		List<Problem> probs = ClientManifestState.problems();
		int x = this.width / 2 - 200;
		int y = 40;
		int max = Math.min(probs.size(), MAX_LINES);
		for (int i = 0; i < max; i++) {
			Problem p = probs.get(i);
			String line = (p.kind() == Kind.MISSING ? "manquant   " : "obsolete   ")
					+ p.entry().id() + "  →  " + p.entry().version()
					+ (p.kind() == Kind.OUTDATED ? "  (installe : " + p.installedVersion() + ")" : "");
			int color = p.kind() == Kind.MISSING ? 0xFFFF6B6B : 0xFFFFD166;
			g.drawString(this.font, line, x, y, color);
			y += this.font.lineHeight + 3;
		}
		if (probs.size() > max) {
			g.drawString(this.font, "… +" + (probs.size() - max) + " autres", x, y, 0xFF909090);
		}

		// Statut + (de)activation des boutons selon l'etat du telechargement.
		ModDownloader.State st = ModDownloader.state();
		if (!ModDownloader.message().isEmpty()) {
			int statusColor = st == ModDownloader.State.ERROR ? 0xFFFF5555
					: st == ModDownloader.State.DONE ? 0xFF55FF55 : 0xFFFFFF55;
			g.drawCenteredString(this.font, ModDownloader.message(), this.width / 2, this.height - 72, statusColor);
		}
		this.downloadButton.active = st != ModDownloader.State.RUNNING && st != ModDownloader.State.DONE;
		this.quitButton.active = st == ModDownloader.State.DONE;
	}
}
