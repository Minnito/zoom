package zoom;

// Rechnungen ohne Minecraft – darum im Selbsttest direkt prüfbar.
// Zoomstufen sind Vergrößerungen (1 = normal, 4 = 4×); Übergänge laufen logarithmisch, damit 2× → 4× sich
// genauso anfühlt wie 8× → 16×.
public final class Optik {
    /** Vergrößerung bei 100 %. */
    public static final double MAX = 50;
    /** Kleinstes Sichtfeld in Grad – darunter flackert die Projektion. */
    static final double MIN_FOV = 0.1;

    /** 0 % = 1×, 100 % = MAX, dazwischen gleichmäßig auf der log-Skala. */
    public static double vergroesserung(int prozent) {
        return Math.pow(MAX, Math.clamp(prozent, 0, 100) / 100.0);
    }

    /** Ein Mausrad-Schritt: 100 % = ×1,25 je Raste. */
    public static double schritt(double m, int richtung, int schrittProzent, double max) {
        return Math.clamp(m * Math.pow(1.25, richtung * schrittProzent / 100.0), 1, Math.max(1, max));
    }

    /** Faktor fürs Sichtfeld (View.fov), damit die Mitte des Bildes m-fach vergrößert ist. */
    public static float fovFaktor(double fovGrad, double m) {
        if (m <= 1) return 1;
        double halb = Math.toRadians(fovGrad) / 2;
        double neu = Math.max(MIN_FOV, Math.toDegrees(2 * Math.atan(Math.tan(halb) / m)));
        return (float) (neu / fovGrad);
    }

    /** Glättet ist → ziel; k = Tempo (1/s), dt in Sekunden. Unabhängig von der Bildrate. */
    public static double naeher(double ist, double ziel, double k, double dt) {
        return Math.exp(Math.log(ziel) + (Math.log(ist) - Math.log(ziel)) * Math.exp(-k * dt));
    }

    /** Sanfter Anfang und sanftes Ende für Übergänge (0…1). */
    public static double weich(double t) {
        return t * t * (3 - 2 * t);
    }

    /** Vergrößerung m, zu t (0…1) eingeblendet. */
    public static double eingeblendet(double m, double t) {
        return Math.pow(m, weich(t));
    }
}
