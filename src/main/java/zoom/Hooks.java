package zoom;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;

// Eingriffe in Minecraft (module.json „patches“, greifen nach einem Neustart). Laufen jedes Bild mitten im Rendern:
// schnell, ohne neue Objekte – und bei einem Fehler nur die eigene Funktion abschalten, nie das Spiel.
public final class Hooks {
    // Hud.extractSpyglassOverlay ist privat; als MethodHandle ohne Boxing aufrufbar. null = Overlay beim Tastenzoom aus
    private static final MethodHandle FERNROHR_OVERLAY = fernrohrOverlay();
    private static boolean zeichneSelbst, kaputt;

    /** FirstPersonHandsAndItemsRenderer.submitHandsWithItems (ersetzen): Hand beim Zoomen weglassen. */
    public static boolean hand(FirstPersonHandsAndItemsRenderer r, float partialTick, PoseStack pose, SubmitNodeCollector c,
                               PlayerRenderState spieler, FirstPersonHandsAndItemsRenderState haende) {
        return Zoom.handAus && Zoom.fortschritt > 0;
    }

    /** Hud.extractRenderState (ersetzen): HUD beim Zoomen ausblenden; das Fernrohr-Overlay zeichnen wir dann selbst. */
    public static boolean hud(Hud hud, GuiGraphicsExtractor g, DeltaTracker d) {
        if (kaputt || hud.isHidden() || !Zoom.hudAus()) return false;
        if (Zoom.overlayAn()) overlay(hud, g, Zoom.overlayGroesse());
        return true;
    }

    /** Hud.extractCameraOverlays (anhängen): Fernrohr-Overlay auch beim Zoom per Taste – unter Hotbar und Chat wie bei Vanilla. */
    public static void overlays(Hud hud, GuiGraphicsExtractor g, DeltaTracker d) {
        if (!kaputt && !Zoom.fernrohr() && Zoom.overlayAn()) overlay(hud, g, Zoom.overlayGroesse());
    }

    /** Hud.extractSpyglassOverlay (ersetzen): Vanillas Overlay beim echten Fernrohr unterdrücken, wenn der Modus es nicht will. */
    public static boolean fernrohrOverlay(Hud hud, GuiGraphicsExtractor g, float groesse) {
        return !zeichneSelbst && !Zoom.overlay.fernrohr;
    }

    /** CameraType.isFirstPerson (anhängen): Third-Person-Zoom aus der Ich-Sicht, damit der Spieler nicht im Bild steht. */
    public static boolean erstePerson(boolean vanilla, CameraType t) {
        return vanilla || t == CameraType.THIRD_PERSON_BACK && Zoom.dritterPerson && Zoom.fortschritt > 0 && !Zoom.fernrohr();
    }

    /** AbstractClientPlayer.getFieldOfViewModifier (anhängen): Vanillas festen 10×-Fernrohrzoom durch unseren ersetzen. */
    public static float fovModifier(float vanilla, AbstractClientPlayer p, boolean erstePerson, float wirkung) {
        return Zoom.fernrohrZoom && erstePerson && p.isScoping() ? 1 : vanilla;
    }

    private static void overlay(Hud hud, GuiGraphicsExtractor g, float groesse) {
        if (FERNROHR_OVERLAY == null) return;
        zeichneSelbst = true;
        try {
            FERNROHR_OVERLAY.invokeExact(hud, g, groesse);
        } catch (Throwable e) {
            kaputt = true;
            System.err.println("[zoom] Fernrohr-Overlay abgeschaltet: " + e);
        } finally {
            zeichneSelbst = false;
        }
    }

    private static MethodHandle fernrohrOverlay() {
        try {
            return MethodHandles.privateLookupIn(Hud.class, MethodHandles.lookup())
                .findVirtual(Hud.class, "extractSpyglassOverlay", MethodType.methodType(void.class, GuiGraphicsExtractor.class, float.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[zoom] Fernrohr-Overlay nicht gefunden: " + e);
            return null;
        }
    }
}
