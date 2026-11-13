# TV Ambilight für Android TV

Ambilight zum Nachrüsten für Android-TVs (z. B. TCL) mit zwei Zigbee-Lampen hinter dem Fernseher, etwa Hue Play Bars links und rechts. Die App liest das Bild direkt am TV aus und steuert die Lampen über **MQTT → Zigbee2MQTT**. Eine Hue Bridge, eine Sync Box oder eine Kamera sind nicht nötig.

![Startseite mit aktivem Ambilight](docs/screenshots/02_start_aktiv.png)

## So funktioniert es

```mermaid
flowchart LR
    A[Bild am TV<br/>Plex, YouTube, …] --> B[Bildschirmaufnahme<br/>160 × 90 px, 15 Bilder/s]
    B --> C[Randzonen<br/>links / rechts]
    C --> D[Farbe aufbereiten<br/>Szenenwechsel · Glättung · Sättigung]
    D -->|nur bei Änderung| E[(MQTT-Broker)]
    E --> F[Zigbee2MQTT]
    F -->|Zigbee| G[Play Bar links]
    F -->|Zigbee| H[Play Bar rechts]
    F -.->|Statusmeldung = Verzögerung| E
    E <-->|Schalter, Einstellungen, Sensoren| I[Home Assistant]
    J[App-Erkennung<br/>Netflix, Plex, …] --> D
```

1. **Bildschirmaufnahme:** Android spiegelt das Bild in einer Mini-Auflösung (160 × 90 Pixel) an die App. Analysiert wird 15-mal pro Sekunde; das kostet kaum Leistung, und für Lichtfarben reicht diese Auflösung völlig.
2. **Randzonen:** Pro Seite wird ein Streifen (Standard 30 % der Bildbreite) zu einer Farbe gemittelt. Schwarze Balken von Kinofilmen (oben/unten) und 4:3-Inhalten (links/rechts) werden erkannt und übersprungen, damit die Lampen nicht vom Balken, sondern vom Bild gesteuert werden.
3. **Farbe aufbereiten:**
   - **Szenenwechsel:** Ein harter Bildschnitt wird sofort und ohne Überblendung übernommen. Alles andere wird geglättet, damit nichts flackert.
   - **Sättigung** anheben, weil gemittelte Farben sonst blass wirken.
   - **Umrechnung in das Hue-Farbsystem** (CIE xy), damit die Farben möglichst originalgetreu ankommen.
   - **Helligkeit** folgt der Bildhelligkeit zwischen einstellbarem Minimum und Maximum, oder bleibt fest (siehe unten).
4. **Sparsam senden:** Zigbee ist das Nadelöhr, nicht die App. Ein Befehl geht nur raus, wenn sich Farbe oder Helligkeit spürbar geändert haben, im normalen Takt höchstens 5-mal pro Sekunde (einstellbar). Szenenwechsel dürfen sofort raus. Die Lampe blendet über `transition` selbst weich über.
5. **Zigbee2MQTT** setzt die Befehle (`zigbee2mqtt/<Lampe>/set`) auf Zigbee um. Seine Statusmeldung danach nutzt die App, um die echte Verzögerung bis zur Lampe zu messen.

## Funktionen

- Ambilight für alle Apps auf dem TV, die ihr Bild nicht schützen (z. B. Plex, Jellyfin, Live-TV-Apps, oft auch YouTube)
- **Szenenwechsel sofort:** harte Schnitte ohne Verzögerung und ohne Überblendung
- **Helligkeitsmodus „Fest“:** nur noch Farbe senden, halbiert die Zigbee-Last (ein statt zwei Befehle pro Update)
- **Ersatzmodus für Netflix, Prime & Co:** feste Farbe pro Lampe, letzte Farbe halten oder Licht aus
- **TV an → Standardlicht, TV aus → Licht aus:** Beim Einschalten gehen die Lampen auf ein frei wählbares Standardlicht (z. B. Warmweiß), bis etwas abgespielt wird; beim Ausschalten gehen sie aus.
- **Pausenlicht:** Wird ein Film pausiert (auch bei Netflix & Co), werden die Lampen nach 1 s z. B. hell warmweiß; beim Weiterschauen übernimmt sofort wieder das Ambilight.
- **App-Profile:** Die App erkennt, was gerade läuft. Netflix & Co → sofort Ersatzfarben ohne schwarze Pause; Startseite → Standardlicht; frei wählbar pro App.
- **Vorheriges Licht wiederherstellen:** Vor dem Start merkt sich die App den Zustand der Lampen und kann ihn bei Standby bzw. Stopp wiederherstellen.
- **Statistik auf der Startseite:** Bilder/s, MQTT- und Zigbee-Befehle/s, Verzögerung bis zur Lampe (Ø und max) und verworfene Updates
- **Home Assistant:** Schalter zum Pausieren, Sensoren (Modus, aktive App, Wiedergabe, Verzögerung, Zigbee-Last) und alle wichtigen Einstellungen als steuerbare Entitäten, automatisch per MQTT-Discovery
- **Einstellungen gelten sofort**, auch während das Ambilight läuft
- Erkennung von Kino- und 4:3-Balken
- Verbindungstest auf Knopfdruck: links rot, rechts blau
- Autostart nach dem Einschalten (optional, braucht ADB-Freigaben)
- Bedienung komplett mit der Fernbedienung
- MQTT mit Benutzer/Passwort, optional TLS. Automatischer Reconnect, Offline-Meldung an Home Assistant bei Verbindungsabbruch.

## Oberfläche

### Startseite

| Gestoppt | Aktiv |
|---|---|
| ![Gestoppt](docs/screenshots/01_start_gestoppt.png) | ![Aktiv](docs/screenshots/02_start_aktiv.png) |
| Startpunkt nach dem Öffnen. **Ambilight starten** fragt einmal die Bildschirmfreigabe an und startet den Hintergrunddienst. | Status, MQTT-Verbindung und erkannte App oben. Rechts die Live-Vorschau der Farben, die gerade an die Lampen gehen, darunter die **Statistik**. Der blaue Rahmen zeigt den Fokus der Fernbedienung. |

| Netflix läuft (App-Profil) | Netflix pausiert (Pausenlicht) |
|---|---|
| ![App-Ersatzmodus](docs/screenshots/03_start_app_ersatzmodus.png) | ![Pausenlicht](docs/screenshots/13_start_pausenlicht.png) |
| Netflix ist als „Sofort Ersatzmodus“ eingetragen: Die Lampen springen beim Öffnen direkt auf die Ersatzfarben, hier links Lila und rechts Blau. Zigbee hat dann Pause. | Film pausiert: Nach 1 s gehen beide Lampen auf das Pausenlicht (hier hell warmweiß). Beim Weiterschauen geht es sofort zurück. |

| Verbindungstest | |
|---|---|
| ![Verbindungstest](docs/screenshots/09_verbindungstest.png) | Prüft Broker, Login und Lampen-Namen, ohne das Ambilight zu starten: Die linke Lampe leuchtet rot, die rechte blau. So siehst du auch sofort, ob links und rechts vertauscht sind. |

### Läuft die App im Hintergrund?

Ja. Es muss nichts zusätzlich installiert werden: Der Dienst ist Teil der App und läuft als Android-Vordergrunddienst (mit Benachrichtigung „TV Ambilight“) weiter, wenn du die App verlässt, die App wechselst oder den TV in den Standby schickst. Er endet erst, wenn du ihn stoppst (Startseite oder Benachrichtigung).

- **TV aus/an (Standby):** Der Dienst bleibt aktiv, schaltet beim Ausschalten die Lampen aus und beim Einschalten auf das Standardlicht.
- **Kaltstart** (Netzstecker, Neustart, Update): Danach läuft der Dienst nur mit **Autostart** wieder an. Dafür braucht es einmalig die ADB-Freigaben `PROJECT_MEDIA` und `SYSTEM_ALERT_WINDOW` (siehe [Installation](#optionale-adb-freigaben)).
- Beendet die TCL-Firmware die Bildaufnahme im Standby, fragt die App sie beim Einschalten automatisch neu an (ebenfalls mit Autostart).

#### Statistik lesen

| Wert | Bedeutung | Richtwert |
|---|---|---|
| Analyse Bilder/s | Wie oft das Bild ausgewertet wird | ~15, solange sich das Bild bewegt; bei Standbild weniger |
| MQTT /s | Befehle an Zigbee2MQTT (beide Lampen) | ≤ 2 × Updates pro Sekunde |
| Zigbee Befehle/s | Was tatsächlich über Funk geht (Farbe und Helligkeit sind je ein Befehl) | Unter ~10 bleibt ein normales Zigbee-Netz entspannt |
| Verzögerung Ø / max | Zeit vom Senden bis zur Statusmeldung von Zigbee2MQTT | < 150 ms gut. Steigt sie stetig, ist das Zigbee-Netz überlastet → Updates pro Sekunde senken oder Helligkeit „Fest“. |
| verworfen | Updates, die durch ein neueres ersetzt wurden, bevor sie raus waren | Nahe 0. Steigt der Wert, hängt der Broker oder das Netz. |

### Einstellungen

| MQTT und Zigbee2MQTT | Bild und Farbe |
|---|---|
| ![MQTT](docs/screenshots/04_einstellungen_mqtt.png) | ![Bild](docs/screenshots/05_einstellungen_bild.png) |
| Broker-Adresse und Port (der Broker, mit dem Z2M verbunden ist), Benutzer, Passwort, optional TLS. Darunter das Z2M-Base-Topic und die **Friendly Names** der beiden Lampen, exakt wie in Z2M. | **Updates pro Sekunde**, **Szenenwechsel sofort**, **Helligkeit** (Dynamisch oder Fest), **Übergangszeit**, **Randzone**, **Glättung**, **Sättigung**, **minimale/maximale Helligkeit**. |

| Ersatzfarben | App-Profile |
|---|---|
| ![Ersatzfarben](docs/screenshots/06_einstellungen_ersatzfarben.png) | ![App-Profile](docs/screenshots/07_einstellungen_app_profile.png) |
| Was bei schwarzem Bild passiert, plus eigene Farbe pro Lampe mit Farbvorschau und Helligkeit. | App-Erkennung an/aus, Status der Berechtigung und drei Listen: **Sofort Ersatzmodus**, **Standardlicht (kein Ambilight)** und **Licht aus**. |

| Standardlicht und Pause | Verhalten |
|---|---|
| ![Standardlicht und Pause](docs/screenshots/14_einstellungen_standard_pause.png) | ![Verhalten](docs/screenshots/08_einstellungen_verhalten_ha.png) |
| **Standardlicht:** Farbe links/rechts und Helligkeit. **Wiedergabe und Pause:** Status der Pause-Erkennung, **Bei Pause** (Pausenlicht, Standardlicht oder Ambilight weiter), Farbe und Helligkeit des Pausenlichts. | **Beim Einschalten des TV** (Standardlicht bis zur Wiedergabe oder sofort Ambilight), **Bei TV-Standby** und **Beim Stoppen** (Licht aus, vorheriger Zustand, Standardlicht oder unverändert), Autostart. |

| Bei Pause | App-Auswahl |
|---|---|
| ![Bei Pause](docs/screenshots/15_dialog_pause.png) | ![App-Auswahl](docs/screenshots/12_dialog_app_profil.png) |
| Was beim Pausieren passiert. | Alle installierten TV-Apps zum Ankreuzen. Netflix, Prime Video, Disney+, Apple TV und DAZN sind für „Sofort Ersatzmodus“ vorausgewählt. |

Alle Änderungen gelten sofort, auch während das Ambilight läuft. Geänderte Broker- oder Lampendaten bauen die Verbindung automatisch neu auf.

### Ersatzmodus für Netflix, Prime & Co

Streaming-Apps mit Kopierschutz (DRM) geben Android bei der Bildschirmaufnahme nur ein schwarzes Bild. Das ist vom System so gewollt und lässt sich per App nicht umgehen. Dafür gibt es zwei Auslöser:

1. **App-Profil (empfohlen):** Die App erkennt Netflix & Co beim Öffnen und schaltet sofort um, ohne schwarze Pause. Braucht einmalig eine ADB-Freigabe (siehe [Installation](#optionale-adb-freigaben)).
2. **Schwarzbild-Erkennung:** Ist das Bild länger als 2 Sekunden **komplett** schwarz, greift der Ersatzmodus ebenfalls. Bis dahin bleibt die letzte Farbe stehen. Normale dunkle Filmszenen lösen das nicht aus, denn echtes Videobild ist praktisch nie exakt schwarz.

| Verhalten wählen | Farbe pro Lampe |
|---|---|
| ![Ersatzmodus](docs/screenshots/10_dialog_ersatzmodus.png) | ![Farbauswahl](docs/screenshots/11_dialog_farbauswahl.png) |
| **Feste Farbe (pro Lampe)**, **Letzte Farbe halten**, **Licht aus** oder **Nichts tun**. | Warm-, Neutral-, Kaltweiß, Rot, Orange, Gelb, Grün, Türkis, Blau, Lila, Pink oder **Eigene Farbe** als Hex-Wert (`#RRGGBB`). |

Sobald wieder ein echtes Bild kommt bzw. die App gewechselt wird, läuft das Ambilight normal weiter.

### Einschalten, Ausschalten und Pause

So läuft ein typischer Fernsehabend mit den Standardeinstellungen:

| Moment | Lampen |
|---|---|
| TV einschalten | **Standardlicht** (z. B. Warmweiß, Helligkeit 150) |
| Startseite, Menüs, App-Auswahl | bleibt Standardlicht |
| Film startet (Plex, YouTube, …) | **Ambilight** |
| Netflix & Co starten | **Ersatzfarben** (App-Profil) |
| Pause (länger als 1 s) | **Pausenlicht** (z. B. hell warmweiß) |
| Weiterschauen | sofort zurück zu Ambilight bzw. Ersatzfarben |
| TV ausschalten | **Licht aus** |

Apps ohne Mediensitzung (z. B. Live-TV) beenden das Standardlicht beim Öffnen sofort, damit nichts hängen bleibt; das braucht die App-Erkennung.

Wiedergabe und Pause erkennt die App über die **Mediensitzungen** von Android. Das ist derselbe Mechanismus, über den Google Assistant oder die Fernbedienung „Pause“ an eine App senden; deshalb funktioniert es auch bei Netflix & Co, obwohl deren Bild geschützt ist. Android gibt diese Information nur an Apps heraus, die als **Benachrichtigungs-Listener** freigegeben sind. Die App nutzt das ausschließlich für den Wiedergabezustand und liest keine Benachrichtigungen. Ohne diese Freigabe startet das Ambilight beim Einschalten direkt, und es gibt kein Pausenlicht.

### Vorheriges Licht wiederherstellen

Beim Start fragt die App Zigbee2MQTT nach dem aktuellen Zustand beider Lampen (an/aus, Helligkeit, Farbe oder Farbtemperatur) und merkt ihn sich. Steht **Bei TV-Standby** oder **Beim Stoppen** auf **Vorheriger Zustand**, wird genau dieser Zustand wiederhergestellt. Hat Home Assistant die Lampen zwischendurch geändert (Pause per HA-Schalter), gilt der neue Zustand als Ausgangspunkt.

## Home Assistant

Mit aktiver MQTT-Discovery erscheint in Home Assistant automatisch das Gerät **TV Ambilight**:

| Entität | Typ | Funktion |
|---|---|---|
| Ambilight | Schalter | Aus = pausiert: Die App sendet nichts mehr, Home Assistant kann die Lampen frei steuern. An = Ambilight läuft weiter. |
| Ambilight Modus | Sensor | `Aktiv`, `Standardlicht`, `Pausenlicht`, `Pausiert (Home Assistant)`, `Geschützter Inhalt`, `Ersatzmodus (App-Profil)`, `Standardlicht (App-Profil)`, `Licht aus (App-Profil)`, `Standby`, `Gestoppt` |
| Aktive App | Sensor | Name der App im Vordergrund, z. B. `Netflix` |
| Wiedergabe | Sensor | `Spielt`, `Pausiert`, `–` |
| Verzögerung, Zigbee-Befehle | Sensoren (Diagnose) | Ø-Verzögerung in ms und Zigbee-Befehle pro Sekunde, alle 10 s aktualisiert |
| Helligkeit maximal/minimal, Updates pro Sekunde, Glättung, Sättigung, Ersatzmodus Helligkeit | Zahlen (Konfiguration) | Schieberegler, wirken sofort |
| Standardlicht Helligkeit, Pausenlicht Helligkeit | Zahlen (Konfiguration) | Schieberegler, wirken sofort |
| Ersatzmodus, Ersatzfarbe links/rechts, Helligkeitsmodus, Beim Einschalten, Bei Pause, Standardlicht links/rechts, Pausenlicht Farbe, Bei TV-Standby, Beim Stoppen | Auswahl (Konfiguration) | Wie in der App |
| Szenenwechsel sofort, App-Erkennung | Schalter (Konfiguration) | Wie in der App |

Ist der TV aus oder die App beendet, werden alle Entitäten als „nicht verfügbar“ angezeigt.

Beispiele (Entitäts-IDs an deine Installation anpassen):

```yaml
# Abends Ambilight dimmen
- alias: Ambilight abends gedimmt
  triggers:
    - trigger: time
      at: "21:30:00"
  actions:
    - action: number.set_value
      target:
        entity_id: number.tv_ambilight_helligkeit_maximal
      data:
        value: 120

# Deckenlicht etwas heller, wenn der Film pausiert
- alias: Deckenlicht bei Pause
  triggers:
    - trigger: state
      entity_id: sensor.tv_ambilight_wiedergabe
      to: "Pausiert"
      for: "00:00:02"
  actions:
    - action: light.turn_on
      target:
        entity_id: light.wohnzimmer_decke
      data:
        brightness_pct: 40

# Deckenlicht dimmen, sobald Netflix & Co laufen
- alias: Deckenlicht dimmen bei Streaming
  triggers:
    - trigger: state
      entity_id: sensor.tv_ambilight_ambilight_modus
      to: "Ersatzmodus (App-Profil)"
  actions:
    - action: light.turn_on
      target:
        entity_id: light.wohnzimmer_decke
      data:
        brightness_pct: 10
```

## Installation

Die fertige APK liegt unter [Releases](https://github.com/hxcde/AndroidTV-Ambilight/releases/latest). Direkter Link auf die jeweils neueste Version:

```
https://github.com/hxcde/AndroidTV-Ambilight/releases/latest/download/TV-Ambilight.apk
```

**Variante A: Sideload direkt am TV (ohne PC)**
- **Downloader-App** aus dem Play Store: den Link oben eingeben, laden, installieren.
- **USB-Stick:** APK draufkopieren und am TV mit einem Dateimanager öffnen (z. B. „X-plore“ oder „File Commander“).
- **„Send files to TV“:** APK vom Handy an den TV schicken.

Android fragt dabei einmalig, ob die App, mit der du installierst, „unbekannte Apps installieren“ darf. Das musst du erlauben.

**Variante B: per ADB vom PC**
```bash
adb install -r TV-Ambilight.apk
```

### Optionale ADB-Freigaben

Ohne ADB funktioniert das Ambilight, du bestätigst dann bei jedem Start den Freigabe-Dialog von Android. Für folgende Extras brauchst du einmalig ADB (vom PC oder per ADB-App vom Handy, Anleitung in [DOKU.md](./DOKU.md#installation-auf-dem-tv)):

- **Pausenlicht und Standardlicht bis zur Wiedergabe** (Wiedergabe/Pause erkennen):
  ```bash
  adb shell cmd notification allow_listener io.github.hxcde.ambilight/io.github.hxcde.ambilight.service.MediaListenerService
  ```
- **App-Profile** (Netflix & Co sofort erkennen):
  ```bash
  adb shell appops set io.github.hxcde.ambilight GET_USAGE_STATS allow
  ```
- Dein TCL zeigt den Freigabe-Dialog **gar nicht** an, oder du willst ohne Dialog starten:
  ```bash
  adb shell appops set io.github.hxcde.ambilight PROJECT_MEDIA allow
  ```
- **Autostart** nach dem Einschalten:
  ```bash
  adb shell appops set io.github.hxcde.ambilight SYSTEM_ALERT_WINDOW allow
  ```

Ob alles freigegeben ist, siehst du in den Einstellungen (**App-Profile → Berechtigung Nutzungsdaten**, **Wiedergabe und Pause → Pause-Erkennung**) und auf der Startseite in den Zeilen **App:** und **Wiedergabe:**.

Empfohlen für den vollen Komfort (TV an/aus, Pause, Netflix): alle vier Befehle einmal ausführen und in der App **Autostart** einschalten.

## Einrichtung

1. App öffnen → **Einstellungen**.
2. **Broker-Adresse:** IP des Rechners bzw. Containers, auf dem der MQTT-Broker läuft (z. B. im Zigbee2MQTT-Container). Port meist `1883`.
3. **Benutzer/Passwort:** am besten einen eigenen MQTT-Benutzer anlegen, z. B. `ambilight`.
4. **Base-Topic:** wie in Z2M (Standard `zigbee2mqtt`). **Lampe links/rechts:** Friendly Names aus der Z2M-Geräteliste.
5. Zurück → **Verbindung testen**: Links muss rot, rechts blau leuchten.
6. **Ambilight starten**, bei Bedarf die Bildschirmfreigabe bestätigen.
7. Unter **Standardlicht** und **Wiedergabe und Pause** die Farben und Helligkeiten nach Geschmack einstellen.
8. Ein paar Minuten schauen und die **Statistik** prüfen. Ist die Verzögerung hoch oder wächst sie, die Updates pro Sekunde senken oder die Helligkeit auf **Fest** stellen.

## Grenzen

- **DRM-Apps** (Netflix, Prime Video, Disney+, teils YouTube) liefern nur Schwarz, dafür gibt es Ersatzmodus und App-Profile.
- **HDMI-Eingänge** des TVs laufen am Android-System vorbei und können nicht ausgelesen werden.
- **Zigbee ist kein Streaming-Protokoll.** Etwa 4–6 Updates pro Sekunde und Lampe sind realistisch (mit Helligkeit „Fest“ etwas mehr); das reicht für flüssiges Stimmungslicht, aber nicht für Blitzeffekte. Das schnellere Hue-Entertainment-Streaming gibt es nur mit Hue Bridge.
- Auf einem echten TV ist die Version 0.3.0 noch nicht getestet. Rückmeldungen zu Update-Rate und Glättung gerne in [DOKU.md](./DOKU.md) nachtragen.

## Entwicklung

```bash
./gradlew assembleDebug
```
```bash
./gradlew testDebugUnitTest
```
```bash
./gradlew recordRoborazziDebug
```

Der letzte Befehl erzeugt die Screenshots in `docs/screenshots/` neu, ohne Emulator. Releases baut GitHub Actions automatisch bei einem Tag `v*` (siehe [DOKU.md](./DOKU.md#deployment--build)).

## Dokumentation

- [CODEINFO.md](./CODEINFO.md): Code-Struktur und Aufbau
- [DOKU.md](./DOKU.md): Einrichtung, Konfiguration, MQTT-Topics, Betrieb, Troubleshooting
