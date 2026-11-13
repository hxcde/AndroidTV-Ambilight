# DOKU — AndroidTV-Ambilight

Einrichtungs-, Admin- und Wartungsdoku.

## Voraussetzungen

- Android TV ab Android 7.0 (API 24), getestet wird gegen API 35
- MQTT-Broker im LAN (z. B. Mosquitto im Zigbee2MQTT-Container oder das Home-Assistant-Add-on)
- Zigbee2MQTT mit zwei Farblampen (z. B. Hue Play Bars). Friendly Names aus der Z2M-Geräteliste.
- Für die Installation: `adb` (Android Platform Tools) auf einem Rechner im selben Netz
- Zum Bauen: JDK 17+, Android SDK (Platform 35, Build-Tools 35). Oder das CI-Artefakt nutzen.

## Konfiguration

Alle Einstellungen werden in der App gesetzt (Einstellungen-Seite) und lokal in den SharedPreferences gespeichert. Backup und Geräteübertragung sind deaktiviert, weil dort das MQTT-Passwort liegt. Änderungen gelten ab dem nächsten Start des Ambilights.

| Bereich | Einstellung | Default | Hinweis |
|---|---|---|---|
| MQTT | Broker-Adresse, Port | –, `1883` | Der Broker, mit dem Z2M verbunden ist (Z2M → Einstellungen → MQTT → `server`) |
| MQTT | TLS | aus | Nur mit gültigem, vom TV vertrautem Zertifikat (meist Port `8883`) |
| MQTT | Benutzer, Passwort | – | Am besten einen eigenen MQTT-Benutzer anlegen, z. B. `ambilight` |
| Z2M | Base-Topic | `zigbee2mqtt` | Z2M → Einstellungen → MQTT → `base_topic` |
| Z2M | Lampe links/rechts | – | Friendly Names exakt wie in Z2M |
| Bild | Updates pro Sekunde | 5 | Pro Lampe. Jede Änderung von Farbe und Helligkeit erzeugt bis zu 2 Zigbee-Befehle. Mehr als 6 belastet das Zigbee-Netz spürbar. |
| Bild | Übergangszeit | 0 (auto = 1/Rate) | `transition` im Z2M-Befehl, die Lampe blendet selbst über |
| Bild | Randzone | 30 % | Anteil der Bildbreite pro Seite |
| Bild | Glättung / Sättigung | 30 % / 130 % | Höhere Glättung = ruhiger, aber träger |
| Bild | Helligkeit min/max | 10 / 254 | Z2M-Skala 1–254 |
| Geschützte Inhalte | Bei schwarzem Bild | Feste Farbe | Feste Farbe pro Lampe, letzte Farbe halten, Licht aus oder nichts tun |
| Geschützte Inhalte | Farbe links/rechts | Warmweiß | Vorgaben oder „Eigene Farbe …“ als `#RRGGBB` |
| Geschützte Inhalte | Helligkeit | 80 | Gilt für beide Lampen im Ersatzmodus |
| Verhalten | Licht aus bei Standby / beim Stoppen | an / an | |
| Verhalten | Autostart | aus | Benötigt die ADB-Freigaben (siehe unten) |
| Home Assistant | MQTT-Discovery, Prefix, Topic | an, `homeassistant`, `tv-ambilight` | |

### MQTT-Topics

| Topic | Richtung | Inhalt |
|---|---|---|
| `<base>/<friendly_name>/set` | App → Z2M | z. B. `{"state":"ON","brightness":180,"color":{"x":0.31,"y":0.33},"transition":0.20}` |
| `<app_topic>/status` | App → HA | `online` / `offline` (retained, LWT) |
| `<app_topic>/state` | App → HA | `ON` = aktiv, `OFF` = pausiert (retained) |
| `<app_topic>/set` | HA → App | `ON` / `OFF`, pausiert das Ambilight, die Lampen sind dann frei für HA |
| `<app_topic>/mode` | App → HA | `Aktiv`, `Pausiert (Home Assistant)`, `Geschützter Inhalt`, `Standby`, `Gestoppt` |
| `<ha_prefix>/switch/…/config`, `<ha_prefix>/sensor/…/config` | App → HA | Discovery (retained). Ist Discovery aus, werden die Configs mit leerem Payload entfernt. |

Beispiel-Automation in HA: Wenn `sensor.tv_ambilight_ambilight_modus` auf `Geschützter Inhalt` wechselt, die Raumbeleuchtung dimmen.

## Installation auf dem TV

1. Entwickleroptionen am TV aktivieren: Einstellungen → System → Info → 7× auf „Build“ (Pfad je nach TCL-Firmware leicht anders).
2. In den Entwickleroptionen „USB-Debugging“ bzw. „Netzwerk-Debugging“ / „Kabelloses Debugging“ aktivieren.
3. Vom Rechner aus verbinden. Ältere Firmware:
   ```bash
   adb connect <TV-IP>:5555
   ```
   Bei „Kabelloses Debugging“ (Android 11+) zuerst mit Code und Port aus dem TV-Dialog koppeln:
   ```bash
   adb pair <TV-IP>:<Pairing-Port>
   ```
4. APK installieren:
   ```bash
   adb install -r app-debug.apk
   ```
5. Bildschirmaufnahme dauerhaft erlauben, ohne Dialog. Nötig, wenn der Dialog bei TCL gar nicht erscheint:
   ```bash
   adb shell appops set io.github.hxcde.ambilight PROJECT_MEDIA allow
   ```
6. Für den Autostart nach dem Booten bzw. Aufwachen aus dem Standby: Start aus dem Hintergrund erlauben:
   ```bash
   adb shell appops set io.github.hxcde.ambilight SYSTEM_ALERT_WINDOW allow
   ```
7. Danach in der App „Autostart“ aktivieren.

Zurücknehmen: dieselben Befehle mit `default` statt `allow`.

## Deployment / Build

- Lokal:
  ```bash
  ./gradlew assembleDebug
  ```
  Ergebnis: `app/build/outputs/apk/debug/app-debug.apk`
- Tests:
  ```bash
  ./gradlew testDebugUnitTest
  ```
- Screenshots neu erzeugen (Robolectric, ohne Emulator):
  ```bash
  ./gradlew recordRoborazziDebug
  ```
  Ausgabe in `docs/screenshots/`.
- CI: `.github/workflows/build.yml` führt bei jedem Push die Tests aus, baut die Debug-APK und lädt sie als Artefakt `app-debug` hoch.
- Signatur: Debug-APKs aus verschiedenen Quellen (lokal oder CI) haben unterschiedliche Debug-Schlüssel. Beim Wechsel vorher deinstallieren:
  ```bash
  adb uninstall io.github.hxcde.ambilight
  ```
  Release-Signatur ist noch nicht eingerichtet (`<offen>`; Keystore nie committen).

## Betrieb & Wartung

- Logs am TV:
  ```bash
  adb logcat -s Ambilight
  ```
- Der Dienst läuft als Vordergrunddienst mit Benachrichtigung „TV Ambilight“; Stoppen über die App oder die Benachrichtigung.
- Bei Standby (Bildschirm aus) werden die Lampen ausgeschaltet. Beendet das System dabei die Bildaufnahme und Autostart ist aktiv, wird die Freigabe beim Einschalten neu angefragt.
- Abhängigkeiten aktualisieren: Versionen in `build.gradle.kts` (Plugins) und `app/build.gradle.kts`.

## Troubleshooting

| Problem | Diagnose | Lösung |
|---|---|---|
| Kein Freigabe-Dialog, App startet nicht | Bekanntes TCL-Verhalten | ADB-Befehl `PROJECT_MEDIA allow` (Installation, Schritt 5) |
| Lampen bleiben bei Netflix/Prime auf fester Farbe | DRM: Android liefert nur Schwarz | Erwartet. Ersatzmodus und Farben in den Einstellungen anpassen. |
| „Verbindung testen“: „Benutzername oder Passwort falsch“ | Broker lehnt Login ab | MQTT-Benutzer prüfen; bei Mosquitto `password_file`/ACL kontrollieren |
| Test OK, aber Lampen reagieren nicht | Falscher Friendly Name oder Base-Topic | Namen exakt wie in Z2M; Topic mit `mosquitto_sub -t '<base>/#' -v` prüfen |
| Lampen ruckeln oder hängen hinterher | Zigbee-Netz überlastet, Z2M-Warteschlange wächst | Updates pro Sekunde auf 3–4 senken, Übergangszeit erhöhen |
| Links und rechts vertauscht | Zuordnung | Friendly Names in den Einstellungen tauschen |
| HDMI-Eingang ohne Ambilight | HDMI-Bild läuft an Android vorbei (Hardware-Overlay) | Nicht lösbar per App |
| Autostart funktioniert nicht | Start aus dem Hintergrund blockiert | `SYSTEM_ALERT_WINDOW allow` per ADB setzen; ggf. Autostart-/Energiesparverwaltung der TCL-Firmware prüfen |
| TLS-Verbindung schlägt fehl | Selbst signiertes Zertifikat | Nicht unterstützt (nur vom System vertraute Zertifikate); im LAN ohne TLS oder mit gültigem Zertifikat |
