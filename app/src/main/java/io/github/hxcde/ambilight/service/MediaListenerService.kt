package io.github.hxcde.ambilight.service

import android.service.notification.NotificationListenerService

/**
 * Nur Platzhalter: Android gibt Mediensitzungen anderer Apps (Wiedergabe/Pause) nur an Apps heraus,
 * die als Benachrichtigungs-Listener freigegeben sind. Benachrichtigungen selbst werden nicht gelesen.
 *
 * Freigabe per ADB:
 * `adb shell cmd notification allow_listener io.github.hxcde.ambilight/io.github.hxcde.ambilight.service.MediaListenerService`
 */
class MediaListenerService : NotificationListenerService()
