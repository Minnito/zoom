# Developer Log: Zoom

| | |
|---|---|
| **Addon** | Zoom (ID `zoom`) |
| **Version** | 1.1.0 |
| **Developer** | Minnito / Claude |
| **Platform** | Client addon for the Swift Client, API 7.1.03, Minecraft 26.3 |
| **Language / build** | Java 25 (`--release 25`), Gradle 9.4.1 (wrapper) |
| **Status** | 2026-10-26 |
| **Build** | `build/libs/zoom.jar`, 19,696 bytes, SHA-256 `f80bb20f9a262e12b28216395f906b4dddb70768dd64750eec51cd130e4c54b6` |
| **Self-test** | `gradlew runTest`: 12 of 12 checks passed (2026-10-26, 12:06, client 7.1.03) |
| **Download** | [Release v1.1.0](https://github.com/Minnito/zoom/releases/tag/v1.1.0) (API 7.1.03). Older releases up to v1.0.0 are built for API 6.2.1 and no longer load on clients from 7.1.03 on |
| **Patch check** | `gradlew check`: 6 of 6 patches OK against Minecraft 26.3 (2026-10-26); in the game 6/6 applied |

---

## 1. Goal

A zoom in the style of Optifine or any other Zoom Mod

## 2. Features

### Zoom (`C` held)
- Zooms while the key is held; the mouse wheel changes the zoom level while zooming (otherwise the wheel stays with vanilla)
- Optional extra keys for zoom in / zoom out (unbound by default)
- Starting zoom and maximum zoom in percent on a log scale: 0 % = 1×, 100 % = 50× (default start 35 % ≈ 4×)
- Step size per scroll notch (100 % = ×1.25)
- Reset zoom: either every zoom starts at the starting zoom, or the last level is remembered (also across restarts)

### Animation
- Smooth zoom in (0.30 s) and out (0.20 s), eased; 0 s = instant
- Smooth scrolling between levels, speed adjustable; independent of the frame rate

### Camera & HUD
- Mouse sensitivity adjusts to the magnification so turning feels normal
- Cinematic camera while zooming (restores your own setting afterwards)
- Third-person rear view zooms from first person, so your player does not block the view
- Hide hand while zooming
- Show HUD: spyglass only, zoom key only, both or never (default: never)

### Spyglass
- The spyglass can use this zoom (mouse wheel, animation) instead of vanilla's fixed 10×
- Spyglass overlay: spyglass only, zoom key only, both or never; drawn with vanilla's own overlay and grow animation
- Spyglass sounds: same four modes; muting only affects your own spyglass

### Servers
- Servers can disable the key zoom with the client feature `zoom` (`disabled-features` in the server plugin); the spyglass keeps working
- Servers running the Just Zoom mod are honored as well (plugin channel `justzoom:zoom_rules`, 1 byte)
- Pressing the zoom key on such a server shows a notice (at most every 5 s)

### Other
- German and English; other languages fall back to English
- Settings split into short sub-pages (Zoom levels, Animation, Camera & HUD, Spyglass, Keys), like MiniMap
- Permissions: `input` (mouse wheel), `server` (Just Zoom channel)

## 3. Architecture

4 classes in package `zoom`, 491 lines in total.

| Class | Lines | Purpose |
|---|---:|---|
| `Zoom` | 349 | Entry point (`client.api.Module`): keys, mouse wheel, transitions, `View.fov` / `View.sensitivity`, sounds, server rule, settings, self-test |
| `Hooks` | 79 | Patch hooks (see below) |
| `Optik` | 44 | Math without Minecraft: percent → magnification, scroll steps, FOV factor, smoothing, easing |
| `T` | 19 | Translations from `assets/zoom/lang/` |

**Data flow:** `tick()` reads the keys and the server rule → `world()` advances the transition and smoothing every frame → `View.fov(factor)` and `View.sensitivity(…)`. The hooks only read the static state in `Zoom`.

**Zoom math:** the zoom is stored as magnification *m*. The new field of view is `2·atan(tan(fov/2) / m)` (at least 0.1°); `View.fov` gets the ratio to the normal FOV. Transitions run on the log scale (`m^ease(t)`), so 2× → 4× feels like 8× → 16×.

### Patches (`module.json`, active after a restart)

| Target | Mode | Hook | Purpose |
|---|---|---|---|
| `FirstPersonHandsAndItemsRenderer.submitHandsWithItems` | replace | `hand` | Hide hand |
| `Hud.extractRenderState` | replace | `hud` | Hide HUD; draws the spyglass overlay itself in that case |
| `Hud.extractCameraOverlays` | append | `overlays` | Spyglass overlay for the key zoom, below hotbar and chat |
| `Hud.extractSpyglassOverlay` | replace | `fernrohrOverlay` | Suppress vanilla's overlay when the mode says so |
| `CameraType.isFirstPerson` | append | `erstePerson` | Third-person zoom from first person |
| `AbstractClientPlayer.getFieldOfViewModifier` | append | `fovModifier` | Replace vanilla's fixed spyglass zoom |

`Hud.extractSpyglassOverlay` is private and is called through a `MethodHandle` (no boxing per frame). If calling it fails, only the overlay switches off.

### Storage locations

| What | Where |
|---|---|
| Settings and last zoom level | `client-modules/zoom.properties` (client API `AddonConfig`) |

## 4. History

All work happened on 2026-10-25.

1. **Analysis.** Disassembled `justzoom_fabric_3.1.0_MC_26.3.jar` with `javap`, read the license (DSMSL v3.1: no code may be reused), evaluated its 14 mixins and listed 15 features to rebuild. Its own options screen, the "press X for options" toast and the Fabric networking were left out because the client provides them.
2. **API comparison.** Checked which features the client API covers (`View.fov`, `View.sensitivity`, `scroll`, `sound`, `ServerInfo.allowed`, `Net`) and which need patches; verified every patch target against the Minecraft 26.3 jar.
3. **Build (version 1.0.0).** Gradle setup taken from MiniMap, own code for all 15 features.
4. **Test.** `runTest` passed 12 of 12 checks; all 6 patches applied. Played with `runClient`: feels very close to Just Zoom.
5. **API 7.1.03 (version 1.1.0, 2026-10-26).** Clients from 7.1.03 on no longer load addons built for older APIs. `module.json` now has `"api": "7.1.03"` and `"minecraft": ["26.3"]`, and `build.gradle` follows the 7.1.03 template. No code changes were needed: every client API call still exists. `gradlew check` passed 6 of 6 patches, `runTest` 12 of 12 checks.

## 5. Tests

`gradlew runTest` starts a local vanilla server (port 25598, offline mode) and the game with the addon. The addon checks itself via `client.api.Testing`: it holds the zoom key virtually in third-person view and checks FOV, camera and mouse wheel. Result from 2026-10-26 (check names are output by the addon in German, translated in brackets):

```
PASS Sichtfeld: 4× bei 70° → 19,85°                          [FOV: 4× at 70° → 19.85°]
PASS Prozent → Vergrößerung (log-Skala)                      [percent → magnification, log scale]
PASS Mausrad: ×1,25 je Raste, in den Grenzen                 [mouse wheel: ×1.25 per notch, within limits]
PASS Glätten erreicht das Ziel                               [smoothing reaches the target]
PASS Modus aus der Config                                    [mode from the config]
PASS Übersetzungen geladen (en_US)                           [translations loaded]
PASS Zoom eingeblendet                                       [zoom faded in]
PASS Sichtfeld verkleinert (Faktor 0.28847852)               [FOV reduced]
PASS Patch: Third Person zoomt aus der Ich-Sicht             [patch: third person zooms from first person]
PASS Mausrad zoomt weiter, solange gezoomt wird              [mouse wheel zooms further while zooming]
PASS Zoom ausgeblendet                                       [zoom faded out]
PASS Danach wieder Vanilla (Sichtfeld, Kamera, Mausrad)      [back to vanilla afterwards]
```

Not covered by the self-test (checked by hand in the game): hide hand, hide HUD, spyglass overlay, sounds, spyglass integration, cinematic camera.

The GitHub Action (`.github/workflows/build.yml`) only builds the jar on every push. `runTest` needs OpenGL and therefore runs locally.

## 6. Known limitations

- **Normal FOV from the options.** The zoom factor is computed from the FOV setting, not from the current FOV with sprint or flying effects. The difference in magnification is a few percent.
- **Zoom in / out keys are unbound.** The client API cannot bind the mouse wheel as a key, so the wheel works directly while zooming and the extra keys start empty.
- **Patches need a restart.** `.addon reload zoom` reloads the code, but changes to the patches only apply after restarting the game.
- **Spyglass sounds.** Muting recognizes your own spyglass by distance (< 2 blocks), so another player's spyglass right next to you is muted too.

## 7. Building and running

Requirement: JDK 25 or newer. The first build downloads Minecraft and its libraries once into `~/.gradle/caches/client-mc`.

```bash
./gradlew build        # → build/libs/zoom.jar, also checks the patches
./gradlew jar          # jar only, without the patch check
./gradlew runClient    # game with the addon, offline as "Dev", folder run/
./gradlew runTest      # test server + game, self-test
./gradlew install      # copy jar into the game; patches need a restart
```

API, patch check and client come as Maven artifacts from `https://api.gta5eclipse.de/maven` (other server: `-PclientServer=…`). The build no longer falls back to the installed launcher, whose client may be older than the API in `module.json`.

## 8. Key bindings

| Key | Action |
|---|---|
| `C` (hold) | Zoom |
| Mouse wheel | Change the zoom level while zooming |
| unbound | Zoom in / zoom out |
