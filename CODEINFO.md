# CODEINFO — AndroidTV-Ambilight

Code-Struktur und Aufbau. Bei strukturellen Änderungen aktualisieren.

## Überblick

Native Android-App (Kotlin, ein Modul `app`, minSdk 24, targetSdk 35) für Android TV. Ein Vordergrunddienst nutzt `MediaProjection`, um den Bildschirm auf 160 px Breite gespiegelt aufzunehmen, mittelt die linke und rechte Randzone und sendet Lampenbefehle über einen eigenen, abhängigkeitsfreien MQTT-3.1.1-Client an Zigbee2MQTT. Keine Server-Komponente; ausgeliefert wird eine APK per Sideload.

## Verzeichnislayout

```
app/src/main/
  AndroidManifest.xml            Activities, Dienst (foregroundServiceType=mediaProjection), Boot-Receiver
  java/io/github/hxcde/ambilight/
    AppSettings.kt               Typisierter Zugriff auf SharedPreferences, Defaults, Schlüssel
    capture/
      ColorMath.kt               sRGB ↔ linear ↔ CIE-xy (Hue-Matrix), Sättigungs-Boost
      FrameAnalyzer.kt           Randzonen-Mittelwert, Letterbox/Pillarbox-Erkennung, Schwarzbild-Erkennung
    light/
      LightOutput.kt             Glättung, Helligkeits-Mapping, Z2M-Payload nur bei Änderung (PipelineConfig, LightState)
      FixedColor.kt              Feste Farben für den Ersatzmodus (Weißtöne als xy, sonst #RRGGBB)
    mqtt/
      MqttPackets.kt             Kodierung der MQTT-Pakete (CONNECT, PUBLISH, SUBSCRIBE, PING …)
      MqttConnection.kt          Socket/TLS, Reconnect mit Backoff, Keepalive, Coalescing-Publish, Verbindungstest
      HomeAssistant.kt           Topics und Discovery-Payloads (Schalter + Modus-Sensor)
    service/
      AmbilightService.kt        Vordergrunddienst: Aufnahme, Zustandsmaschine, Licht, MQTT, Benachrichtigung
      AmbilightState.kt          StateFlow mit Laufzeitstatus für die Oberfläche
    ui/
      MainActivity.kt            Startseite: Start/Stopp, Status, Farbvorschau, Verbindungstest
      SettingsActivity.kt        PreferenceFragmentCompat, Eingabetypen, Sichtbarkeitslogik Ersatzfarben
      CaptureRequestActivity.kt  Unsichtbar: fragt Bildschirmfreigabe an und startet den Dienst
      BootReceiver.kt            Autostart nach BOOT_COMPLETED
  res/xml/preferences.xml        Alle Einstellungen (Schlüssel = AppSettings.K_*)
  res/values/arrays.xml          Auswahllisten (Ersatzmodus, Farbvorgaben)
app/src/test/                    JVM-Unit-Tests + Robolectric/Roborazzi-Screenshots (ui/ScreenshotTest.kt)
docs/screenshots/                Erzeugte Screenshots (TV-Auflösung 1920×1080)
.github/workflows/build.yml      CI: Tests, APK-Build, Upload als Artefakt
.github/workflows/release.yml    Release bei Tag v* oder manuell: APK als Release-Asset TV-Ambilight.apk
```

## Module & Zuständigkeiten

- **capture**: reines Kotlin ohne Android-Abhängigkeiten, als JVM-Test prüfbar. `FrameAnalyzer.analyze()` bekommt RGBA-Bytes inkl. `rowStride`/`pixelStride` aus dem `ImageReader` und liefert lineare Farben je Seite sowie `isBlack`.
- **light**: `LightOutput` pro Lampe. `target()` glättet (EMA im linearen Raum), erhöht die Sättigung, rechnet in xy um und bildet `max(R,G,B)` auf `brightness_min..max` ab. `payloadFor()` vergleicht mit dem zuletzt gesendeten Zustand und sendet nur geänderte Felder (Farbe und Helligkeit sind getrennte Zigbee-Befehle).
- **mqtt**: `MqttConnection` schreibt nur auf einem Thread. `publishLatest()` behält pro Topic nur den neuesten Payload, damit sich bei langsamem Broker nichts aufstaut. Nur QoS 0. Mit TLS werden Zertifikat und Hostname geprüft.
- **service**: `AmbilightService` hält die gesamte Laufzeitlogik auf einem `HandlerThread`. Zustände: `ACTIVE`, `PAUSED` (über Home Assistant), `PROTECTED` (Schwarzbild ≥ 2 s), `STANDBY` (Bildschirm aus).
- **ui**: Oberfläche für Fernbedienung (D-Pad, Fokusrahmen). Einstellungen über AndroidX Preference.

## Zusammenspiel / Datenfluss

```
MediaProjection → VirtualDisplay (160×90, AUTO_MIRROR) → ImageReader (RGBA)
  → FrameAnalyzer (Randzonen links/rechts, linear)
  → LightOutput.target() (Glättung, Sättigung, xy, Helligkeit)
  → LightOutput.payloadFor() (nur Änderungen)
  → MqttConnection.publishLatest("<base>/<friendly_name>/set")
  → Broker → Zigbee2MQTT → Zigbee → Lampe
```

Ersatzmodus: Ist das Bild ≥ 2 s komplett schwarz (DRM), wird je nach `black_mode` eine feste Farbe pro Lampe gesendet, die letzte Farbe gehalten oder das Licht ausgeschaltet. Sobald wieder Bild kommt, läuft das Ambilight normal weiter.

Home Assistant: Der Dienst abonniert `<app_topic>/set` (ON/OFF = aktiv/pausiert) und `<ha_prefix>/status` (Birth-Message), veröffentlicht `status` (mit LWT `offline`), `state` und `mode` retained sowie die Discovery-Configs.

## Wichtige Einstiegspunkte

- `ui/MainActivity.kt`: Launcher-Activity (LAUNCHER + LEANBACK_LAUNCHER)
- `service/AmbilightService.kt`: `onStartCommand()` (Start mit Freigabe-Intent), `onFrame()`, `checkBlackTimeout()`
- `AppSettings.kt`: alle Einstellungen und Defaults
- `app/build.gradle.kts`: SDK-Versionen, Abhängigkeiten, Release-Signatur über Umgebungsvariablen `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
