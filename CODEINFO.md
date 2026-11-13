# CODEINFO — AndroidTV-Ambilight

Code-Struktur und Aufbau. Bei strukturellen Änderungen aktualisieren.

## Überblick

Native Android-App (Kotlin, ein Modul `app`, minSdk 24, targetSdk 35) für Android TV. Ein Vordergrunddienst nutzt `MediaProjection`, um den Bildschirm auf 160 px Breite gespiegelt aufzunehmen (~15 Bilder/s), mittelt die linke und rechte Randzone und sendet Lampenbefehle über einen eigenen, abhängigkeitsfreien MQTT-3.1.1-Client an Zigbee2MQTT. Die Steuerlogik (Modi, Senderaster, Szenenwechsel, Zustand sichern/wiederherstellen, Statistik) steckt Android-frei in `engine/AmbilightEngine` und ist per JVM-Test abgedeckt. Keine Server-Komponente; ausgeliefert wird eine APK per Sideload.

## Verzeichnislayout

```
app/src/main/
  AndroidManifest.xml            Activities, Dienst (foregroundServiceType=mediaProjection), Boot-Receiver
  java/io/github/hxcde/ambilight/
    AppSettings.kt               Typisierter Zugriff auf SharedPreferences, Defaults, Schlüssel, EngineConfig
    Options.kt                   Auswahllisten (Wert + Anzeigetext) für Einstellungen und HA-Selects
    capture/
      ColorMath.kt               sRGB ↔ linear ↔ CIE-xy (Hue-Matrix), Sättigungs-Boost
      FrameAnalyzer.kt           Randzonen-Mittelwert, Letterbox/Pillarbox-Erkennung, Schwarzbild-Erkennung
    engine/
      AmbilightEngine.kt         Steuerlogik ohne Android: Modi, Senderaster, Szenenwechsel, Ersatzmodus, Snapshot, Statistik
      LightSnapshot.kt           Lampenzustand aus Z2M-Statusmeldung lesen und als Befehl wiederherstellen
      Types.kt                   Mode, LightAction, AppAction, Playback, PauseAction, WakeAction, LightScene, AppProfiles, EngineStats
    light/
      LightOutput.kt             Bildratenunabhängige Glättung, Szenenwechsel, Helligkeit dynamisch/fest, Z2M-Payload nur bei Änderung
      FixedColor.kt              Feste Farben für den Ersatzmodus (Weißtöne als xy, sonst #RRGGBB)
    mqtt/
      MqttPackets.kt             Kodierung der MQTT-Pakete (CONNECT, PUBLISH, SUBSCRIBE, PING …)
      MqttConnection.kt          Socket/TLS, Reconnect mit Backoff, Keepalive, Coalescing-Publish, Verbindungstest
      HomeAssistant.kt           Topics und Discovery-Payloads (Schalter, Sensoren, Einstellungs-Entitäten)
      HaEntities.kt              Per HA steuerbare Einstellungen (number/select/switch/light) inkl. Wert-Parsing
      ExternalState.kt           TV-Status aus HA (aktive App, Media-Player-Zustand) parsen
    service/
      AmbilightService.kt        Vordergrunddienst: Aufnahme, MQTT/HA, Live-Einstellungen, App-Erkennung → Engine
      ForegroundAppDetector.kt   Vordergrund-App per UsageStatsManager (Berechtigung GET_USAGE_STATS), Launcher-Liste
      MediaWatcher.kt            Wiedergabe/Pause aus den Mediensitzungen (MediaSessionManager)
      MediaListenerService.kt    Leerer NotificationListenerService, nur als Schlüssel für die Mediensitzungen
      AmbilightState.kt          StateFlow mit Laufzeitstatus und Statistik für die Oberfläche
    ui/
      MainActivity.kt            Startseite: Start/Stopp, Status, Farbvorschau, Verbindungstest
      SettingsActivity.kt        PreferenceFragmentCompat, Optionslisten, App-Auswahl, Farbfelder, Berechtigungsstatus
      SetupActivity.kt           Einrichtungsseite: Status der optionalen Freigaben, TV-Einstellung, ADB-Befehle
      Permissions.kt             Prüft PROJECT_MEDIA, Benachrichtigungszugriff, Nutzungsdaten, Overlay
      RemoteControlActivity.kt   Exportiert, ohne UI: tvambilight://start|stop für Home Assistant/ADB
      CaptureRequestActivity.kt  Unsichtbar: fragt Bildschirmfreigabe an und startet den Dienst
      BootReceiver.kt            Autostart nach BOOT_COMPLETED
  res/xml/preferences.xml        Alle Einstellungen (Schlüssel = AppSettings.K_*)
app/src/test/                    JVM-Unit-Tests (u. a. engine/AmbilightEngineTest), MQTT-Integrationstest (MQTT_IT_PORT), Robolectric/Roborazzi-Screenshots
docs/screenshots/                Erzeugte Screenshots (TV-Auflösung 1920×1080)
blueprints/automation/tv_ambilight/  Home-Assistant-Blueprints (mit TV starten, TV-Status übergeben, Raumlicht folgt Wiedergabe, abends dimmen)
blueprints/script/tv_ambilight/      Skript-Blueprint „Freigaben setzen“ (androidtv.adb_command)
.github/workflows/build.yml      CI: Tests, APK-Build, Upload als Artefakt
.github/workflows/release.yml    Release bei Tag v* oder manuell: APK als Release-Asset TV-Ambilight.apk
```

## Module & Zuständigkeiten

- **capture**: reines Kotlin ohne Android-Abhängigkeiten, als JVM-Test prüfbar. `FrameAnalyzer.analyze()` bekommt RGBA-Bytes inkl. `rowStride`/`pixelStride` aus dem `ImageReader` und liefert lineare Farben je Seite sowie `isBlack`.
- **engine**: `AmbilightEngine` bekommt Bilder (`onFrame`), Statusmeldungen der Lampen (`onLightState`), Bildschirm an/aus, HA-Pause, die App-Aktion und den Wiedergabezustand (`setPlayback`) und gibt MQTT-Nachrichten über `EngineOutput.publish` aus. Modus-Priorität: Standby > Pause (HA) > App-Profil Aus/Standardlicht > Pausenlicht (≥ 1 s pausiert) > Standardlicht nach Einschalten bis zur Wiedergabe > App-Profil Ersatzmodus > geschützter Inhalt > aktiv. Feste Szenen (`LightScene`): Standardlicht, Pausenlicht. Senderaster pro Lampe `sendIntervalMs`, Szenenwechsel dürfen nach 100 ms raus. Vor dem ersten Senden wird der Lampenzustand per `…/get` gesichert (max. 2 s Wartezeit).
- **light**: `LightOutput` pro Lampe. `target()` glättet zeitbasiert (`keep = smoothing^(dt/interval)`), erkennt Szenenwechsel (max. sRGB-Kanaldifferenz > 0,25), erhöht die Sättigung, rechnet in xy um und bildet `max(R,G,B)` auf `brightness_min..max` ab, oder hält sie fest. `payloadFor()` sendet nur geänderte Felder.
- **mqtt**: `MqttConnection` schreibt nur auf einem Thread. `publishLatest()` behält pro Topic nur den neuesten Payload und zählt ersetzte als `dropped`. Nur QoS 0. Mit TLS werden Zertifikat und Hostname geprüft. `HaEntities` beschreibt die steuerbaren Einstellungen; `HomeAssistant` erzeugt Discovery und Topics.
- **service**: `AmbilightService` verbindet Android mit der Engine: Aufnahme auf einem `HandlerThread`, Bildschirm-Broadcasts, App-Erkennung und Mediensitzungen (`MediaWatcher` über `MediaListenerService`, 1 s bzw. per Callback), Statistik (2 s, an HA alle 10 s). Ein `OnSharedPreferenceChangeListener` übernimmt Änderungen live; Verbindungsschlüssel (`AppSettings.CONNECTION_KEYS`) lösen einen MQTT-Neuaufbau aus.
- **ui**: Oberfläche für Fernbedienung (D-Pad, Fokusrahmen). Einstellungen über AndroidX Preference, Auswahllisten aus `Options.kt`.

## Zusammenspiel / Datenfluss

```
MediaProjection → VirtualDisplay (160×90, AUTO_MIRROR) → ImageReader (RGBA), ~15 Bilder/s
  → FrameAnalyzer (Randzonen links/rechts, linear, Schwarzbild)
  → AmbilightEngine.onFrame()
      Modus ≠ aktiv → nichts (Ersatzfarben/Wiederherstellen wurden beim Moduswechsel gesendet)
      LightOutput.target() (Szenenwechsel, Glättung, Sättigung, xy, Helligkeit)
      Senderaster / Szenenwechsel → LightOutput.payloadFor() (nur Änderungen)
  → MqttConnection.publishLatest("<base>/<friendly_name>/set")
  → Broker → Zigbee2MQTT → Zigbee → Lampe
  ← Z2M-Statusmeldung "<base>/<friendly_name>" → onLightState() (Snapshot, Verzögerung)

UsageStatsManager (1 s) bzw. HA "<app>/external/app" → AppProfiles.actionFor(pkg) → engine.setAppAction()
MediaSessionManager (Callback) bzw. HA "<app>/external/playback" → engine.setPlayback()
SharedPreferences-Listener → engine.updateConfig() bzw. MQTT-Neuaufbau
HA "<app>/config/<id>/set" → SharedPreferences → Listener (wie oben)
```

Ersatzmodus: Ist das Bild ≥ 2 s komplett schwarz (DRM) oder läuft eine App mit Profil „Sofort Ersatzmodus“, wird je nach `black_mode` eine feste Farbe pro Lampe gesendet, die letzte Farbe gehalten oder das Licht ausgeschaltet.

Home Assistant: Der Dienst abonniert `<app_topic>/set` (Pause), `<app_topic>/config/+/set` (Einstellungen inkl. Licht-Entitäten), `<app_topic>/command` (`STOP`), `<ha_prefix>/status` (Birth-Message) und die Statusmeldungen beider Lampen; er veröffentlicht `status` (LWT `offline`), `state`, `mode`, `app`, `playback`, `live` (alle 2 s bei Änderung), `stats`, alle `config/<id>`-Werte und die Discovery-Configs. Gestartet wird von außen über `RemoteControlActivity` (`tvambilight://start`), weil ein gestoppter Dienst per MQTT nicht erreichbar ist.

## Wichtige Einstiegspunkte

- `ui/MainActivity.kt`: Launcher-Activity (LAUNCHER + LEANBACK_LAUNCHER)
- `service/AmbilightService.kt`: `onStartCommand()` (Start mit Freigabe-Intent), `connect()`, `applySetting()`, `onMqttMessage()`
- `engine/AmbilightEngine.kt`: `onFrame()`, `updateMode()`, `startSnapshot()`, `collectStats()`
- `AppSettings.kt`: alle Einstellungen und Defaults
- `app/build.gradle.kts`: SDK-Versionen, Abhängigkeiten, Release-Signatur über Umgebungsvariablen `RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`
