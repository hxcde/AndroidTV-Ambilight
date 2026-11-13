# DOKU — AndroidTV-Ambilight

Einrichtungs-, Admin- und Wartungsdoku.

## Voraussetzungen

- Android TV ab Android 7.0 (API 24), getestet wird gegen API 35
- MQTT-Broker im LAN (z. B. Mosquitto im Zigbee2MQTT-Container oder das Home-Assistant-Add-on)
- Zigbee2MQTT mit zwei Farblampen (z. B. Hue Play Bars). Friendly Names aus der Z2M-Geräteliste.
- Für die Installation: `adb` (Android Platform Tools) auf einem Rechner im selben Netz
- Zum Bauen: JDK 17+, Android SDK (Platform 35, Build-Tools 35). Oder das CI-Artefakt nutzen.

## Konfiguration

Alle Einstellungen werden in der App gesetzt (Einstellungen-Seite) und lokal in den SharedPreferences gespeichert. Backup und Geräteübertragung sind deaktiviert, weil dort das MQTT-Passwort liegt. Änderungen gelten sofort, auch im laufenden Betrieb; geänderte Broker-, Topic- oder Lampendaten bauen die MQTT-Verbindung automatisch neu auf. Die mit (HA) markierten Einstellungen lassen sich zusätzlich aus Home Assistant ändern.

| Bereich | Einstellung | Default | Hinweis |
|---|---|---|---|
| MQTT | Broker-Adresse, Port | –, `1883` | Der Broker, mit dem Z2M verbunden ist (Z2M → Einstellungen → MQTT → `server`) |
| MQTT | TLS | aus | Nur mit gültigem, vom TV vertrautem Zertifikat (meist Port `8883`) |
| MQTT | Benutzer, Passwort | – | Am besten einen eigenen MQTT-Benutzer anlegen, z. B. `ambilight` |
| Z2M | Base-Topic | `zigbee2mqtt` | Z2M → Einstellungen → MQTT → `base_topic` |
| Z2M | Lampe links/rechts | – | Friendly Names exakt wie in Z2M |
| Bild | Updates pro Sekunde (HA) | 5 | Normaler Sendetakt pro Lampe. Analysiert wird unabhängig davon mit ~15 Bildern/s. |
| Bild | Szenenwechsel sofort (HA) | an | Harter Bildschnitt (Kanaländerung > 25 %) wird sofort (frühestens 100 ms nach dem letzten Befehl) mit `transition` 0 gesendet |
| Bild | Helligkeit (HA) | Dynamisch | „Fest“: Helligkeit bleibt auf Maximum, es geht nur noch der Farbbefehl raus (1 statt 2 Zigbee-Befehle) |
| Bild | Übergangszeit | 0 (auto = 1/Rate) | `transition` im Z2M-Befehl, die Lampe blendet selbst über |
| Bild | Randzone | 30 % | Anteil der Bildbreite pro Seite |
| Bild | Glättung / Sättigung (HA) | 30 % / 130 % | Glättung = Anteil der alten Farbe pro Sendeintervall, unabhängig von der Bildrate |
| Bild | Helligkeit min/max (HA) | 10 / 254 | Z2M-Skala 1–254 |
| Geschützte Inhalte | Bei schwarzem Bild (HA) | Feste Farbe | Feste Farbe pro Lampe, letzte Farbe halten, Licht aus oder nichts tun. Gilt auch für App-Profil „Sofort Ersatzmodus“. |
| Geschützte Inhalte | Farbe links/rechts (HA) | Warmweiß | Vorgaben oder „Eigene Farbe …“ als `#RRGGBB` (Hex nur in der App) |
| Geschützte Inhalte | Helligkeit (HA) | 80 | Gilt für beide Lampen im Ersatzmodus |
| App-Profile | App-Erkennung (HA) | an | Braucht `GET_USAGE_STATS` (siehe Installation) |
| App-Profile | Sofort Ersatzmodus | Netflix, Prime Video, Disney+, Apple TV, DAZN | Ohne 2-s-Wartezeit, auch in den (ungeschützten) Menüs dieser Apps |
| App-Profile | Standardlicht / Licht aus | – | Standardlicht = Szene „Standardlicht“, Ambilight ruht. Priorität: Licht aus > Standardlicht > Ersatzmodus. |
| Standardlicht | Farbe links/rechts, Helligkeit (HA) | Warmweiß, 150 | Feste Szene für Einschalten, App-Profil, Pause oder Standby/Stopp |
| Wiedergabe und Pause | Bei Pause (HA) | Pausenlicht | Pausenlicht, Standardlicht oder Ambilight weiter; greift nach 1 s Pause. Braucht die Freigabe als Benachrichtigungs-Listener. |
| Wiedergabe und Pause | Pausenlicht Farbe, Helligkeit (HA) | Warmweiß, 230 | Gilt für beide Lampen |
| Verhalten | Beim Einschalten des TV (HA) | Standardlicht bis zur Wiedergabe | Ohne Pause-Erkennung startet das Ambilight sofort |
| Verhalten | Bei TV-Standby (HA) | Licht aus | Licht aus, vorheriger Zustand, Standardlicht oder unverändert |
| Verhalten | Beim Stoppen (HA) | Vorheriger Zustand | wie oben |
| Verhalten | Autostart | aus | Benötigt die ADB-Freigaben (siehe unten) |
| Home Assistant | MQTT-Discovery, Prefix, Topic | an, `homeassistant`, `tv-ambilight` | |

### Vorheriger Zustand

Beim ersten Verbinden sendet die App an beide Lampen `<base>/<friendly_name>/get` mit `{"state":"","brightness":"","color":"","color_temp":""}` und wartet bis zu 2 s auf die Statusmeldung von Z2M. Erst danach beginnt das Ambilight. Gespeichert werden Schaltzustand, Helligkeit und je nach `color_mode` Farbe (xy) oder Farbtemperatur. Antwortet Z2M nicht, wird beim Wiederherstellen ausgeschaltet. Nach einer Pause durch Home Assistant oder dem Profil „Normales Licht“ wird der Zustand neu gesichert.

### Verzögerungsmessung

Nach jedem Befehl veröffentlicht Z2M den neuen Zustand auf `<base>/<friendly_name>`. Die Zeit zwischen Senden und dieser Meldung ist die angezeigte Verzögerung (gleitend über die letzten 30 Messungen). Ist in Z2M für die Lampen `debounce` gesetzt, ist der Wert entsprechend höher.

### MQTT-Topics

| Topic | Richtung | Inhalt |
|---|---|---|
| `<base>/<friendly_name>/set` | App → Z2M | z. B. `{"state":"ON","brightness":180,"color":{"x":0.31,"y":0.33},"transition":0.20}` |
| `<app_topic>/status` | App → HA | `online` / `offline` (retained, LWT) |
| `<app_topic>/state` | App → HA | `ON` = aktiv, `OFF` = pausiert (retained) |
| `<app_topic>/set` | HA → App | `ON` / `OFF`, pausiert das Ambilight, die Lampen sind dann frei für HA |
| `<base>/<friendly_name>/get` | App → Z2M | Zustandsabfrage beim Start (für „Vorheriger Zustand“) |
| `<base>/<friendly_name>` | Z2M → App | Statusmeldung: Ausgangszustand und Verzögerungsmessung |
| `<app_topic>/mode` | App → HA | `Aktiv`, `Standardlicht`, `Pausenlicht`, `Pausiert (Home Assistant)`, `Geschützter Inhalt`, `Ersatzmodus (App-Profil)`, `Standardlicht (App-Profil)`, `Licht aus (App-Profil)`, `Standby`, `Gestoppt` (retained) |
| `<app_topic>/app` | App → HA | Name der App im Vordergrund (retained) |
| `<app_topic>/playback` | App → HA | `Spielt`, `Pausiert`, `–` (retained) |
| `<app_topic>/stats` | App → HA | alle 10 s: `{"latency_ms":84,"latency_max_ms":152,"zigbee_per_s":8.9,"messages_per_s":6.4,"fps":15.0,"dropped":0}` |
| `<app_topic>/config/<id>` | App → HA | aktueller Wert einer Einstellung (retained); bei Lichtern JSON, z. B. `{"state":"ON","color_mode":"rgb","brightness":150,"color":{"r":255,"g":195,"b":122}}` |
| `<app_topic>/config/<id>/set` | HA → App | neuer Wert; Zahlen werden gerundet und begrenzt, Auswahlen per Anzeigetext, Lichter als JSON mit `color` (r/g/b) und/oder `brightness` |
| `<app_topic>/command` | HA → App | `STOP` beendet das Ambilight (Button „Ambilight beenden“) |
| `<app_topic>/live` | App → HA | höchstens alle 2 s bei Änderung: `{"left":"#E0702A","right":"#2A8FE0","left_rgb":[224,112,42],"right_rgb":[42,143,224]}` |
| `<ha_prefix>/<switch\|sensor\|number\|select\|light\|button>/<node>/<id>/config` | App → HA | Discovery (retained). Ist Discovery aus, werden die Configs mit leerem Payload entfernt. Configs älterer Versionen (z. B. `select/black_color_left`) werden immer entfernt. |

IDs für `config/<id>`: `brightness_max`, `brightness_min`, `update_rate`, `smoothing`, `saturation`, `black_mode`, `brightness_mode`, `standby_action`, `wake_action`, `pause_action`, `stop_action`, `scene_cut`, `app_detection` sowie die Lichter `black_light_left`, `black_light_right`, `standard_light_left`, `standard_light_right`, `pause_light`.

Beispiel ohne Home Assistant:
```bash
mosquitto_pub -h <broker> -u <user> -P <pass> -t tv-ambilight/config/brightness_max/set -m 120
```

## Start und Stopp von außen

Die App reagiert auf die Links `tvambilight://start` und `tvambilight://stop` (exportierte `RemoteControlActivity`, ohne Oberfläche):

- Home Assistant mit Integration „Android TV Remote“: `remote.turn_on` mit `activity: tvambilight://start`
- ADB: `adb shell am start -a android.intent.action.VIEW -d tvambilight://start`
- Alternativ per Action: `adb shell am start -a io.github.hxcde.ambilight.action.START` (bzw. `…STOP`)

Läuft das Ambilight schon, bewirkt `start` nichts. Gestartet wird immer über die normale Bildschirmfreigabe: ohne `PROJECT_MEDIA`-Freigabe erscheint der Dialog am TV. Da jede App bzw. jeder Link diese Adressen aufrufen kann, ist die Funktion bewusst auf Start/Stopp beschränkt.

## Installation auf dem TV

Kurzfassung: Sideloaden genügt. Welche optionalen Freigaben fehlen, zeigt die Seite **Einrichtung** in der App (mit Knopf zur TV-Einstellung, falls vorhanden, und ADB-Befehl). Alle Freigaben auf einmal:

```bash
adb shell "appops set io.github.hxcde.ambilight PROJECT_MEDIA allow; cmd notification allow_listener io.github.hxcde.ambilight/io.github.hxcde.ambilight.service.MediaListenerService; appops set io.github.hxcde.ambilight GET_USAGE_STATS allow; appops set io.github.hxcde.ambilight SYSTEM_ALERT_WINDOW allow"
```

Einzeln:

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
5. App-Profile (App im Vordergrund erkennen):
   ```bash
   adb shell appops set io.github.hxcde.ambilight GET_USAGE_STATS allow
   ```
   Alternativ am TV unter Einstellungen → Apps → Spezieller App-Zugriff → Nutzungsdatenzugriff, falls die TCL-Firmware diesen Punkt anbietet. Die App öffnet ihn über „Berechtigung Nutzungsdaten“.
6. Wiedergabe/Pause erkennen (Pausenlicht, Standardlicht bis zur Wiedergabe):
   ```bash
   adb shell cmd notification allow_listener io.github.hxcde.ambilight/io.github.hxcde.ambilight.service.MediaListenerService
   ```
   Die App registriert dafür einen Benachrichtigungs-Listener, liest aber keine Benachrichtigungen, nur den Wiedergabezustand der Mediensitzungen. Zurücknehmen mit `disallow_listener` statt `allow_listener`.
7. Bildschirmaufnahme dauerhaft erlauben, ohne Dialog. Nötig, wenn der Dialog bei TCL gar nicht erscheint:
   ```bash
   adb shell appops set io.github.hxcde.ambilight PROJECT_MEDIA allow
   ```
8. Für den Autostart nach dem Booten bzw. Aufwachen aus dem Standby: Start aus dem Hintergrund erlauben:
   ```bash
   adb shell appops set io.github.hxcde.ambilight SYSTEM_ALERT_WINDOW allow
   ```
9. Danach in der App „Autostart“ aktivieren.

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

### Release

`.github/workflows/release.yml` baut die APK und legt einen GitHub-Release mit dem Asset `TV-Ambilight.apk` an. Der Dateiname bleibt immer gleich, daher zeigt `https://github.com/hxcde/AndroidTV-Ambilight/releases/latest/download/TV-Ambilight.apk` stets auf die neueste Version.

Auslösen:
- per Tag (vorher `versionName`/`versionCode` in `app/build.gradle.kts` erhöhen):
  ```bash
  git tag v0.2.0
  ```
  ```bash
  git push origin v0.2.0
  ```
- oder in GitHub unter Actions → Release → „Run workflow“ mit Tag, z. B. `v0.2.0`.

### Signatur

Ohne hinterlegten Schlüssel signiert der Release-Workflow mit dem Debug-Schlüssel des CI-Laufs. Der wechselt bei jedem Lauf, Updates erfordern dann eine Deinstallation:

```bash
adb uninstall io.github.hxcde.ambilight
```

Für Updates ohne Deinstallation einmalig einen eigenen Schlüssel anlegen (lokal, sicher aufbewahren, **nie committen**):

```bash
keytool -genkeypair -v -keystore tv-ambilight-release.jks -alias ambilight -keyalg RSA -keysize 4096 -validity 10000
```
```bash
base64 -w0 tv-ambilight-release.jks
```

Dann im Repo unter Settings → Secrets and variables → Actions diese Secrets anlegen: `RELEASE_KEYSTORE_BASE64` (Ausgabe von `base64`), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`ambilight`), `RELEASE_KEY_PASSWORD`. Ab dann baut der Workflow eine signierte, mit R8 optimierte Release-APK. Beim Wechsel von Debug- auf Release-Signatur einmal deinstallieren.

## Betrieb & Wartung

- Logs am TV:
  ```bash
  adb logcat -s Ambilight
  ```
- Der Dienst läuft als Vordergrunddienst mit Benachrichtigung „TV Ambilight“ dauerhaft im Hintergrund, auch über TV-Standby hinweg; Stoppen über die App oder die Benachrichtigung. Nach einem Kaltstart startet er nur mit Autostart (ADB-Freigaben) neu.
- Bei Standby (Bildschirm aus) werden die Lampen je nach Einstellung wiederhergestellt oder ausgeschaltet. Beendet das System dabei die Bildaufnahme und Autostart ist aktiv, wird die Freigabe beim Einschalten neu angefragt.
- Feintuning: Statistik auf der Startseite bzw. Sensor „Verzögerung“ in HA beobachten. Steigt die Verzögerung stetig oder „verworfen“ wächst, Updates pro Sekunde senken oder Helligkeit „Fest“ wählen.
- Abhängigkeiten aktualisieren: Versionen in `build.gradle.kts` (Plugins) und `app/build.gradle.kts`.

## Troubleshooting

| Problem | Diagnose | Lösung |
|---|---|---|
| Kein Freigabe-Dialog, App startet nicht | Bekanntes TCL-Verhalten | ADB-Befehl `PROJECT_MEDIA allow` (Installation, Schritt 5) |
| Lampen bleiben bei Netflix/Prime auf fester Farbe | DRM: Android liefert nur Schwarz | Erwartet. Ersatzmodus und Farben in den Einstellungen anpassen. |
| „Verbindung testen“: „Benutzername oder Passwort falsch“ | Broker lehnt Login ab | MQTT-Benutzer prüfen; bei Mosquitto `password_file`/ACL kontrollieren |
| Test OK, aber Lampen reagieren nicht | Falscher Friendly Name oder Base-Topic | Namen exakt wie in Z2M; Topic mit `mosquitto_sub -t '<base>/#' -v` prüfen |
| Lampen ruckeln oder hängen hinterher | Statistik: Verzögerung hoch/steigend, Zigbee-Befehle/s > 10 | Updates pro Sekunde auf 3–4 senken, Helligkeit „Fest“, Übergangszeit erhöhen; Koordinator näher an die Bars, Zigbee-Kanal abseits des WLANs |
| Verzögerung zeigt „–“ | Keine Statusmeldung von Z2M | Lampen-Namen prüfen; Z2M-Option `output` (json) und kein `retain`/`debounce`-Sonderfall |
| „Wiedergabe: Pause-Erkennung nicht freigegeben“ | Benachrichtigungs-Listener nicht freigegeben | `cmd notification allow_listener …` per ADB (Installation, Schritt 6) |
| Kein Pausenlicht, obwohl freigegeben | App meldet keine Mediensitzung oder Pause dauerte < 1 s | Startseite zeigt „Wiedergabe: …“; bleibt sie bei „–“, unterstützt die App das nicht |
| Nach dem Einschalten bleibt Standardlicht | Keine Wiedergabe erkannt | Wiedergabe starten genügt. Apps ohne Mediensitzung (z. B. Live-TV) beenden das Standardlicht beim Öffnen automatisch, sofern die App-Erkennung freigegeben ist. Sonst „Beim Einschalten“ auf „Sofort Ambilight“ stellen. |
| Start aus Home Assistant passiert nichts | Android-TV-Remote-Integration öffnet den Link nicht, oder Dialog am TV wartet | Am TV nachsehen, ob „Jetzt starten“ angezeigt wird; `PROJECT_MEDIA` freigeben. Test per ADB: `am start -a android.intent.action.VIEW -d tvambilight://start` |
| Alte Entitäten (z. B. „Ersatzfarbe links“ als Auswahl) bleiben in HA | Discovery-Config älterer Version | Wird beim Verbinden automatisch entfernt; sonst Entität in HA löschen |
| App: „Berechtigung fehlt“ | Nutzungsdatenzugriff nicht erteilt | `GET_USAGE_STATS allow` per ADB (Installation, Schritt 5) |
| Profil greift nicht | App nicht in der Liste oder falsche App erkannt | Startseite zeigt „App: …“; diese App in den App-Profilen ankreuzen |
| Nach Stopp falsches Licht | Zustand beim Start nicht gelesen (Z2M antwortete nicht binnen 2 s) | Log prüfen (`adb logcat -s Ambilight`); notfalls „Beim Stoppen: Licht aus“ |
| Links und rechts vertauscht | Zuordnung | Friendly Names in den Einstellungen tauschen |
| HDMI-Eingang ohne Ambilight | HDMI-Bild läuft an Android vorbei (Hardware-Overlay) | Nicht lösbar per App |
| Autostart funktioniert nicht | Start aus dem Hintergrund blockiert | `SYSTEM_ALERT_WINDOW allow` per ADB setzen; ggf. Autostart-/Energiesparverwaltung der TCL-Firmware prüfen |
| TLS-Verbindung schlägt fehl | Selbst signiertes Zertifikat | Nicht unterstützt (nur vom System vertraute Zertifikate); im LAN ohne TLS oder mit gültigem Zertifikat |
