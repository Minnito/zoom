package zoom;

import client.api.AddonConfig;
import client.api.Keys;
import client.api.Module;
import client.api.Net;
import client.api.Notify;
import client.api.Page;
import client.api.ServerInfo;
import client.api.Setting;
import client.api.Sounds;
import client.api.Tasks;
import client.api.Testing;
import client.api.View;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;

// Zoom im Stil von JustZoom, eigener Code: Taste halten = zoomen, Mausrad = Stufe, sanfte Übergänge, Maus passt sich an.
// Sichtfeld und Maus laufen über View (ohne Patches); Hand, HUD, Fernrohr-Overlay, Third Person und der Vanilla-Fernrohrzoom
// über die Patches in Hooks.java. Zoom-Zustand und Einstellungen sind statisch, weil die Hooks sie jedes Bild lesen.
public final class Zoom implements Module {
    static final String ID = "zoom";
    private static final String TON_AN = "minecraft:item.spyglass.use", TON_AUS = "minecraft:item.spyglass.stop_using";

    /** Wann eine Funktion gilt: beim echten Fernrohr, beim Zoom per Taste, bei beidem oder nie. */
    enum Modus {
        NUR_FERNROHR(true, false), NUR_TASTE(false, true), BEIDE(true, true), NIE(false, false);

        final boolean fernrohr, taste;

        Modus(boolean fernrohr, boolean taste) {
            this.fernrohr = fernrohr;
            this.taste = taste;
        }

        static Modus von(String name, Modus standard) {
            for (Modus m : values()) if (m.name().equals(name)) return m;
            return standard;
        }
    }

    // ───── Einstellungen (zoom.properties) ─────
    static int basis, maximum, schritt, scrollTempo;
    static boolean weich, zuruecksetzen, weicheKamera, mausAnpassen, dritterPerson, handAus, fernrohrZoom;
    static double startDauer, stoppDauer;
    static Modus hud, overlay, toene;

    // ───── Zustand ─────
    static boolean tasteZoom;           // Zoom-Taste gedrückt und erlaubt
    static double fortschritt;          // Übergang: 0 = aus, 1 = voll gezoomt
    static double ziel = 1, ist = 1;    // Vergrößerung: Ziel (Mausrad) und aktuell (geglättet)
    static float faktor = 1;            // zuletzt an View.fov gegeben
    private static boolean serverRegel = true;   // JustZoom-Server-Mod: Zoom per Taste erlaubt?
    private static long eigenerTonBis;           // bis dahin sind Fernrohr-Töne von uns – nicht stummschalten

    private final AddonConfig cfg = AddonConfig.of(ID);
    private final KeyMapping zoomKey = Keys.key(ID, "zoom", InputConstants.KEY_C);
    private final KeyMapping reinKey = Keys.key(ID, "zoom_in", InputConstants.UNKNOWN.getValue());
    private final KeyMapping rausKey = Keys.key(ID, "zoom_out", InputConstants.UNKNOWN.getValue());
    private boolean vorherGedrueckt, vorherZoomt, kameraVorher, kameraGesetzt, testTaste;
    private long letztesBild, letzteWarnung;

    public Zoom() {
        basis = cfg.number("basis", 35);
        maximum = cfg.number("maximum", 100);
        schritt = cfg.number("schritt", 100);
        scrollTempo = cfg.number("scroll_tempo", 100);
        weich = cfg.bool("weich", true);
        startDauer = cfg.decimal("start", 0.3);
        stoppDauer = cfg.decimal("stopp", 0.2);
        zuruecksetzen = cfg.bool("zuruecksetzen", false);
        weicheKamera = cfg.bool("weiche_kamera", false);
        mausAnpassen = cfg.bool("maus", true);
        dritterPerson = cfg.bool("dritte_person", true);
        handAus = cfg.bool("hand_aus", true);
        fernrohrZoom = cfg.bool("fernrohr_zoom", true);
        hud = Modus.von(cfg.text("hud", ""), Modus.NIE);
        overlay = Modus.von(cfg.text("overlay", ""), Modus.NUR_FERNROHR);
        toene = Modus.von(cfg.text("toene", ""), Modus.BEIDE);
        ziel = ist = cfg.decimal("letzter", Optik.vergroesserung(basis));
        // Server mit der JustZoom-Mod schicken beim Betreten, ob der Zoom per Taste erlaubt ist (1 Byte: Boolean)
        Net.on("justzoom:zoom_rules", d -> serverRegel = d.length == 0 || d[0] != 0);
    }

    static boolean fernrohr() {
        var p = Minecraft.getInstance().player;
        return p != null && p.isScoping();
    }

    /** Zoom gerade gewünscht – per Taste oder mit dem Fernrohr (wenn es unseren Zoom nutzt). */
    static boolean zoomt() {
        return tasteZoom || fernrohrZoom && fernrohr();
    }

    /** HUD gerade ausblenden? Fernrohr und Taste haben je ihren Modus. */
    static boolean hudAus() {
        return fernrohr() ? !hud.fernrohr : fortschritt > 0 && !hud.taste;
    }

    static boolean overlayAn() {
        return fernrohr() ? overlay.fernrohr : fortschritt > 0 && overlay.taste;
    }

    /** Größe des Fernrohr-Overlays wie bei Vanilla (0,5 → 1,125), mit unserem Übergang. */
    static float overlayGroesse() {
        double t = fernrohr() && !fernrohrZoom ? 1 : fortschritt;
        return (float) (0.5 + 0.625 * Optik.weich(t));
    }

    static boolean eigenerTon() {
        return System.nanoTime() < eigenerTonBis;
    }

    private static double maxM() {
        return Optik.vergroesserung(maximum);
    }

    private static double basisM() {
        return Math.min(Optik.vergroesserung(basis), maxM());
    }

    @Override
    public List<KeyMapping> keys() {
        return List.of(zoomKey, reinKey, rausKey);
    }

    @Override
    public void tick() {
        boolean gedrueckt = zoomKey.isDown() || testTaste;
        boolean erlaubt = serverRegel && ServerInfo.allowed("zoom");
        if (gedrueckt && !vorherGedrueckt && !erlaubt) warnen();
        vorherGedrueckt = gedrueckt;
        boolean taste = gedrueckt && erlaubt && Minecraft.getInstance().player != null;
        if (taste != tasteZoom && !fernrohr() && toene.taste) ton(taste ? TON_AN : TON_AUS);
        tasteZoom = taste;

        boolean an = zoomt();
        if (an && !vorherZoomt) {
            if (zuruecksetzen) ziel = basisM();
            ziel = Math.clamp(ziel, 1, Math.max(1, maxM()));
            ist = ziel;   // Übergang läuft direkt auf die Zielstufe, nicht vom letzten Rest
        } else if (!an && vorherZoomt) cfg.set("letzter", ziel);
        vorherZoomt = an;
        while (reinKey.consumeClick()) stufe(1);
        while (rausKey.consumeClick()) stufe(-1);
    }

    @Override
    public boolean scroll(double amount) {
        return amount != 0 && stufe(amount > 0 ? 1 : -1);
    }

    /** Eine Stufe rein (1) oder raus (-1); false, wenn gerade nicht gezoomt wird (Mausrad bleibt dann bei Vanilla). */
    private boolean stufe(int richtung) {
        if (!zoomt()) return false;
        ziel = Optik.schritt(ziel, richtung, schritt, maxM());
        if (!weich) ist = ziel;
        return true;
    }

    // Jedes Bild: Übergang und Glättung weiterrechnen, dann Sichtfeld und Maus setzen
    @Override
    public void world(float partialTick) {
        long jetzt = System.nanoTime();
        double dt = letztesBild == 0 ? 0 : Math.min(0.1, (jetzt - letztesBild) / 1e9);
        letztesBild = jetzt;
        boolean an = zoomt();
        if (!weich) {
            fortschritt = an ? 1 : 0;
            ist = ziel;
        } else {
            double dauer = an ? startDauer : stoppDauer;
            fortschritt = dauer <= 0 ? (an ? 1 : 0) : Math.clamp(fortschritt + (an ? dt : -dt) / dauer, 0, 1);
            ist = Optik.naeher(ist, ziel, 12 * scrollTempo / 100.0, dt);
        }
        anwenden();
    }

    private void anwenden() {
        var mc = Minecraft.getInstance();
        double m = Optik.eingeblendet(ist, fortschritt);
        faktor = fortschritt > 0 ? Optik.fovFaktor(mc.options.fov().get(), m) : 1;
        View.fov(faktor);
        float maus = mausAnpassen && fortschritt > 0 ? (float) (1 / m) : 1;
        // Vanilla bremst die Maus beim Fernrohr auf 1/8 – mit unserem Fernrohrzoom regelt das stattdessen die Zeile darüber
        if (fernrohrZoom && fernrohr() && mc.options.getCameraType().isFirstPerson()) maus *= 8;
        View.sensitivity(maus);
        kamera(fortschritt > 0 && weicheKamera);
    }

    // Filmische Kamera nur beim Zoomen; danach wieder so, wie der Spieler sie hatte
    private void kamera(boolean an) {
        var o = Minecraft.getInstance().options;
        if (an && !kameraGesetzt) {
            kameraVorher = o.smoothCamera;
            o.smoothCamera = true;
            kameraGesetzt = true;
        } else if (!an && kameraGesetzt) {
            o.smoothCamera = kameraVorher;
            kameraGesetzt = false;
        }
    }

    private static void ton(String id) {
        eigenerTonBis = System.nanoTime() + 200_000_000L;
        Sounds.play(id, 1, 1);
    }

    @Override
    public boolean sound(SoundInstance s) {
        if (eigenerTon() || toene.fernrohr) return false;
        String id = s.getIdentifier().toString();
        if (!id.equals(TON_AN) && !id.equals(TON_AUS)) return false;
        var p = Minecraft.getInstance().player;
        return p != null && p.distanceToSqr(s.getX(), s.getY(), s.getZ()) < 4;   // nur das eigene Fernrohr
    }

    private void warnen() {
        long jetzt = System.currentTimeMillis();
        if (jetzt - letzteWarnung < 5000) return;
        letzteWarnung = jetzt;
        Notify.show(T.t("name"), T.t("server.gesperrt"));
    }

    @Override
    public void join(String server) {
        serverRegel = true;
        if (Testing.active()) Tasks.later(40, this::selbsttest);
    }

    @Override
    public void leave() {
        serverRegel = true;
        tasteZoom = false;
        fortschritt = 0;
        anwenden();
    }

    @Override
    public void unload() {
        kamera(false);
    }

    // ───── Einstellungen ─────

    @Override
    public List<Setting> options() {
        // Kurze Unterseiten wie bei MiniMap: der Client springt nach jedem Schalter an den Anfang der Liste
        return List.of(
            seite("zoom", () -> List.of(
                prozent("basis", 0, 100, () -> basis, v -> basis = v),
                prozent("maximum", 0, 100, () -> maximum, v -> maximum = v),
                prozent("schritt", 10, 300, () -> schritt, v -> schritt = v),
                schalter("zuruecksetzen", () -> zuruecksetzen, v -> zuruecksetzen = v))),
            seite("animation", () -> List.of(
                schalter("weich", () -> weich, v -> weich = v),
                sekunden("start", () -> startDauer, v -> startDauer = v).visibleIf(() -> weich),
                sekunden("stopp", () -> stoppDauer, v -> stoppDauer = v).visibleIf(() -> weich),
                prozent("scroll_tempo", 10, 300, () -> scrollTempo, v -> scrollTempo = v).visibleIf(() -> weich))),
            seite("kamera", () -> List.of(
                schalter("maus", () -> mausAnpassen, v -> mausAnpassen = v),
                schalter("weiche_kamera", () -> weicheKamera, v -> weicheKamera = v),
                schalter("dritte_person", () -> dritterPerson, v -> dritterPerson = v),
                schalter("hand_aus", () -> handAus, v -> handAus = v),
                modus("hud", () -> hud, v -> hud = v))),
            seite("fernrohr", () -> List.of(
                schalter("fernrohr_zoom", () -> fernrohrZoom, v -> fernrohrZoom = v),
                modus("overlay", () -> overlay, v -> overlay = v),
                modus("toene", () -> toene, v -> toene = v))),
            seite("tasten", () -> List.of(Setting.key(zoomKey), Setting.key(reinKey), Setting.key(rausKey))));
    }

    // Name und Beschreibung jeder Einstellung aus o.<key> und o.<key>.info; gespeichert unter <key>
    private Setting schalter(String k, BooleanSupplier get, Consumer<Boolean> set) {
        return Setting.toggle(T.t("o." + k), T.t("o." + k + ".info"), get, v -> {
            set.accept(v);
            cfg.set(k, v);
        });
    }

    private Setting prozent(String k, int min, int max, IntSupplier get, IntConsumer set) {
        return Setting.slider(T.t("o." + k), T.t("o." + k + ".info"), min, max, get, v -> {
            set.accept(v);
            cfg.set(k, v);
        }, v -> v + " %");
    }

    private Setting sekunden(String k, DoubleSupplier get, DoubleConsumer set) {
        return Setting.decimal(T.t("o." + k), T.t("o." + k + ".info"), 0, 2, 0.05, get, v -> {
            set.accept(v);
            cfg.set(k, v);
        }, v -> String.format(T.locale(), "%.2f s", v));
    }

    private Setting modus(String k, Supplier<Modus> get, Consumer<Modus> set) {
        return Setting.choice(T.t("o." + k), T.t("o." + k + ".info"), List.of(Modus.values()), get, v -> {
            set.accept(v);
            cfg.set(k, v.name());
        }, v -> T.t("modus." + v.name().toLowerCase(Locale.ROOT)));
    }

    private static Setting seite(String name, Supplier<List<Setting>> inhalt) {
        String titel = T.t("seite." + name);
        return Setting.button(titel, T.t("seite." + name + ".info"), T.t("oeffnen"), () -> Page.open(titel, inhalt));
    }

    // ───── Selbsttest (gradlew runTest) ─────

    private void selbsttest() {
        Testing.check(Optik.fovFaktor(70, 1) == 1 && Math.abs(Optik.fovFaktor(70, 4) - 19.85 / 70) < 1e-3,
            "Sichtfeld: 4× bei 70° → 19,85°");
        Testing.check(Optik.vergroesserung(0) == 1 && Math.abs(Optik.vergroesserung(100) - Optik.MAX) < 1e-9
            && Math.abs(Optik.vergroesserung(50) - Math.sqrt(Optik.MAX)) < 1e-9, "Prozent → Vergrößerung (log-Skala)");
        Testing.check(Optik.schritt(Optik.MAX, 1, 100, Optik.MAX) == Optik.MAX && Optik.schritt(1, -1, 100, Optik.MAX) == 1
            && Math.abs(Optik.schritt(2, 1, 100, Optik.MAX) - 2.5) < 1e-9, "Mausrad: ×1,25 je Raste, in den Grenzen");
        Testing.check(Math.abs(Optik.naeher(1, 4, 12, 10) - 4) < 1e-6 && Optik.naeher(1, 4, 12, 0) == 1, "Glätten erreicht das Ziel");
        Testing.check(Modus.von("NUR_TASTE", Modus.NIE) == Modus.NUR_TASTE && Modus.von("kaputt", Modus.NIE) == Modus.NIE, "Modus aus der Config");
        Testing.check(!T.t("name").startsWith(ID + ".") && !T.t("modus.nie").startsWith(ID + "."), "Übersetzungen geladen (" + T.locale() + ")");

        var o = Minecraft.getInstance().options;
        var vorher = o.getCameraType();
        o.setCameraType(CameraType.THIRD_PERSON_BACK);
        testTaste = true;
        Testing.await("Zoom eingeblendet", () -> fortschritt >= 1, 100, () -> {
            Testing.check(faktor < 0.5f, "Sichtfeld verkleinert (Faktor " + faktor + ")");
            Testing.check(o.getCameraType().isFirstPerson(), "Patch: Third Person zoomt aus der Ich-Sicht");
            double vorStufe = ziel;
            Testing.check(scroll(1) && ziel > vorStufe, "Mausrad zoomt weiter, solange gezoomt wird");
            testTaste = false;
            Testing.await("Zoom ausgeblendet", () -> fortschritt == 0, 100, () -> {
                Testing.check(faktor == 1 && !o.getCameraType().isFirstPerson() && !scroll(1), "Danach wieder Vanilla (Sichtfeld, Kamera, Mausrad)");
                o.setCameraType(vorher);
                Testing.done();
            });
        });
    }
}
