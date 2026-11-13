package io.github.hxcde.ambilight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Mode(val label: String) {
    STOPPED("Gestoppt"),
    ACTIVE("Aktiv"),
    PAUSED("Pausiert (Home Assistant)"),
    PROTECTED("Geschützter Inhalt"),
    STANDBY("Standby"),
}

data class Status(
    val mode: Mode = Mode.STOPPED,
    val mqttConnected: Boolean = false,
    val mqttError: String? = null,
    val leftColor: Int = 0xFF000000.toInt(),
    val rightColor: Int = 0xFF000000.toInt(),
)

/** Laufzeitstatus des Dienstes für die Oberfläche (gleicher Prozess). */
object AmbilightState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(block: (Status) -> Status) = _status.update(block)

    fun reset() {
        _status.value = Status()
    }
}
