#!/usr/bin/env bash
# TV Ambilight auf dem Fernseher installieren oder aktualisieren, ohne Einstellungen zu verlieren.
#
# Ablauf:
#   1. Versucht ein normales Update (adb install -r). Klappt das, bleibt alles erhalten.
#   2. Passt die Signatur nicht (z. B. Wechsel zwischen Release- und Einzel-APK), werden die
#      Einstellungen per run-as gesichert, die App neu installiert und die Einstellungen zurückgespielt.
#   3. Setzt die optionalen Freigaben (ohne Dialog starten, Pause-/App-Erkennung, Autostart).
#   4. Startet das Ambilight im Hintergrund (ohne die App-Oberfläche zu öffnen).
#
# Aufruf (macOS/Linux):
#   scripts/tv-update.sh [APK] [TV-IP[:PORT]] [--open | --no-start] [--restore DATEI]
#
#   APK          Pfad zur APK. Ohne Angabe wird die neueste Release-Version von GitHub geladen.
#   TV-IP        Wird per "adb connect" verbunden. Ohne Angabe wird das bereits verbundene Gerät genutzt.
#   --open       Öffnet danach die App-Oberfläche statt das Ambilight direkt zu starten.
#   --no-start   Startet danach nichts.
#   --restore F  Spielt eine gesicherte Einstellungsdatei ein (z. B. nach manueller Deinstallation).
#
# Sicherungen liegen in ~/.tv-ambilight-backups (nur für dich lesbar), denn sie enthalten das MQTT-Passwort.

set -euo pipefail

PKG="io.github.hxcde.ambilight"
PREFS="shared_prefs/${PKG}_preferences.xml"
LISTENER="$PKG/$PKG.service.MediaListenerService"
LATEST_URL="https://github.com/hxcde/AndroidTV-Ambilight/releases/latest/download/TV-Ambilight.apk"
BACKUP_DIR="${TV_AMBILIGHT_BACKUP_DIR:-$HOME/.tv-ambilight-backups}"

APK=""
TV=""
AFTER="start"
RESTORE=""

info() { printf '\033[1;34m▶\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --start) AFTER="start" ;;
        --open) AFTER="open" ;;
        --no-start) AFTER="none" ;;
        --restore) shift; [ $# -gt 0 ] || fail "--restore braucht eine Datei"; RESTORE="$1" ;;
        -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *.apk) APK="$1" ;;
        *) TV="$1" ;;
    esac
    shift
done

command -v adb >/dev/null 2>&1 || fail "adb nicht gefunden (macOS: brew install --cask android-platform-tools)"
[ -z "$RESTORE" ] || [ -s "$RESTORE" ] || fail "Sicherung nicht gefunden oder leer: $RESTORE"

# ---------------------------------------------------------------- Gerät
if [ -n "$TV" ]; then
    case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac
    info "Verbinde mit $TV …"
    adb connect "$TV" >/dev/null || true
    export ANDROID_SERIAL="$TV"
fi
state="$(adb get-state 2>&1 || true)"
case "$state" in
    device) ;;
    *unauthorized*) fail "Am TV die Meldung 'USB-Debugging zulassen?' bestätigen und erneut starten." ;;
    *more\ than\ one*) fail "Mehrere Geräte verbunden. TV-IP angeben, z. B.: $0 192.168.1.50" ;;
    *) fail "Kein Gerät verbunden. TV-IP angeben oder vorher: adb connect <TV-IP>:5555 ($state)" ;;
esac
ok "Verbunden mit $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

# ---------------------------------------------------------------- APK
TMPDIR_UPD="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_UPD"' EXIT
if [ -z "$APK" ]; then
    info "Lade die neueste Version von GitHub …"
    APK="$TMPDIR_UPD/TV-Ambilight.apk"
    curl -fsSL -o "$APK" "$LATEST_URL" || fail "Download fehlgeschlagen: $LATEST_URL"
fi
[ -s "$APK" ] || fail "APK nicht gefunden: $APK"

installed_version() {
    adb shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1
}
is_installed() { adb shell pm path "$PKG" 2>/dev/null | grep -q '^package:'; }

# ---------------------------------------------------------------- Einstellungen
backup_settings() {
    mkdir -p "$BACKUP_DIR"
    chmod 700 "$BACKUP_DIR"
    local file
    file="$BACKUP_DIR/ambilight-settings-$(date +%Y%m%d-%H%M%S).xml"
    (umask 077; adb exec-out run-as "$PKG" cat "$PREFS" > "$file") 2>/dev/null || true
    if ! grep -q '<map' "$file" 2>/dev/null; then
        rm -f "$file"
        return 1
    fi
    echo "$file"
}

restore_settings() {
    local file="$1"
    local tmp="/data/local/tmp/ambilight-settings.xml"
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb push "$file" "$tmp" >/dev/null
    if ! adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp $tmp $PREFS'" >/dev/null 2>&1; then
        # Manche Geräte erlauben run-as keinen Zugriff auf /data/local/tmp: über die Standardeingabe schreiben
        adb exec-in run-as "$PKG" sh -c "mkdir -p shared_prefs && cat > $PREFS" < "$file"
    fi
    adb shell rm -f "$tmp" >/dev/null 2>&1 || true
    if adb exec-out run-as "$PKG" cat "$PREFS" 2>/dev/null | cmp -s - "$file"; then
        ok "Einstellungen zurückgespielt"
    else
        warn "Einstellungen konnten nicht geprüft werden. Sicherung liegt in: $file"
    fi
}

grant_permissions() {
    info "Setze Freigaben …"
    if adb shell "appops set $PKG PROJECT_MEDIA allow; cmd notification allow_listener $LISTENER; appops set $PKG GET_USAGE_STATS allow; appops set $PKG SYSTEM_ALERT_WINDOW allow" >/dev/null 2>&1; then
        ok "Freigaben gesetzt"
    else
        warn "Freigaben konnten nicht (alle) gesetzt werden; siehe Einrichtung in der App"
    fi
}

# ---------------------------------------------------------------- Installation
before=""
if is_installed; then before="$(installed_version)"; fi
info "Installiere $(basename "$APK")${before:+ (installiert: $before)} …"

backup=""
if is_installed && [ -z "$RESTORE" ]; then
    # Vorsorglich immer sichern; schadet nicht und hilft, falls doch etwas schiefgeht
    backup="$(backup_settings || true)"
    [ -n "$backup" ] && ok "Einstellungen gesichert: $backup"
fi

out="$(adb install -r -d "$APK" 2>&1 || true)"
if printf '%s' "$out" | grep -q '^Success'; then
    if [ -n "$before" ]; then ok "Update installiert, Einstellungen bleiben erhalten"; else ok "Installiert"; fi
elif printf '%s' "$out" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match'; then
    warn "Andere Signatur als die installierte Version: Neuinstallation mit Sicherung"
    [ -n "$backup" ] || fail "Einstellungen ließen sich nicht sichern (keine Debug-Version?). Abbruch, nichts verändert."
    adb uninstall "$PKG" >/dev/null || fail "Deinstallation fehlgeschlagen"
    out="$(adb install "$APK" 2>&1 || true)"
    printf '%s' "$out" | grep -q '^Success' || fail "Installation fehlgeschlagen: $out
Einstellungen liegen in: $backup (später mit --restore einspielen)"
    ok "Neu installiert"
    RESTORE="$backup"
else
    fail "Installation fehlgeschlagen: $out"
fi

[ -z "$RESTORE" ] || restore_settings "$RESTORE"
grant_permissions
ok "Version jetzt: $(installed_version)"

case "$AFTER" in
    start)
        # Unsichtbarer Start über tvambilight://start; mit PROJECT_MEDIA-Freigabe ohne Dialog
        if adb shell am start -a android.intent.action.VIEW -d tvambilight://start >/dev/null 2>&1; then
            ok "Ambilight gestartet (läuft im Hintergrund)"
        else
            warn "Ambilight konnte nicht gestartet werden; App am TV öffnen und starten"
        fi
        ;;
    open) adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1 && ok "App geöffnet" ;;
    none) ;;
esac
