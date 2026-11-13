# AndroidTV-Ambilight

Ambilight zum Nachrüsten für Android TV (z. B. TCL): Die App nimmt den Bildschirm in sehr kleiner Auflösung auf, berechnet die Farbe der linken und rechten Bildkante und schickt sie per MQTT an Zigbee2MQTT, das damit zwei Lampen ansteuert, etwa Hue Play Bars links und rechts hinter dem TV. Eine Hue Bridge ist nicht nötig. Home Assistant kann das Ambilight per MQTT-Discovery pausieren.

> Grenze: Netflix, Prime Video und andere DRM-Apps liefern Android nur ein schwarzes Bild. Dafür gibt es den Ersatzmodus mit fester Farbe pro Lampe (z. B. links Lila, rechts Blau).

| Start (aktiv) | Ersatzfarben |
|---|---|
| ![Start](docs/screenshots/02_start_aktiv.png) | ![Ersatzfarben](docs/screenshots/06_einstellungen_ersatzfarben.png) |

## Setup

1. APK bauen (oder aus dem GitHub-Actions-Artefakt `app-debug` laden):
   ```bash
   ./gradlew assembleDebug
   ```
   Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`
2. Auf den TV installieren (ADB im Netzwerk, Details in [DOKU.md](./DOKU.md#installation-auf-dem-tv)):
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
3. Empfohlen: Freigaben per ADB setzen. Damit entfällt der Freigabe-Dialog, und der Autostart funktioniert:
   ```bash
   adb shell appops set io.github.hxcde.ambilight PROJECT_MEDIA allow
   ```
   ```bash
   adb shell appops set io.github.hxcde.ambilight SYSTEM_ALERT_WINDOW allow
   ```
4. App öffnen, **Einstellungen**: MQTT-Broker (Host, Port, Benutzer, Passwort), Z2M-Base-Topic und die Friendly Names der beiden Lampen eintragen.
5. **Verbindung testen**: Links leuchtet rot, rechts blau. Danach **Ambilight starten**.

## Dokumentation

- [CODEINFO.md](./CODEINFO.md): Code-Struktur und Aufbau
- [DOKU.md](./DOKU.md): Einrichtung, Konfiguration, Betrieb, Troubleshooting
- [docs/screenshots/](./docs/screenshots/): Screenshots der Oberfläche (per `./gradlew recordRoborazziDebug` erzeugt)

## Status

Build, Unit-Tests und Screenshots laufen automatisch. Auf einem echten TV ist die App noch nicht getestet. Erfahrungswerte zu Update-Rate und Glättung bitte in [DOKU.md](./DOKU.md) nachtragen.
