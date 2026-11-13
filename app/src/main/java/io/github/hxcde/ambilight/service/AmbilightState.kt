package io.github.hxcde.ambilight.service

import io.github.hxcde.ambilight.engine.EngineStats
import io.github.hxcde.ambilight.engine.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class Status(
    val mode: Mode = Mode.STOPPED,
    val mqttConnected: Boolean = false,
    val mqttError: String? = null,
    val leftColor: Int = 0xFF000000.toInt(),
    val rightColor: Int = 0xFF000000.toInt(),
    val stats: EngineStats? = null,
    val dropped: Long = 0,
    /** Anzeigename der App im Vordergrund (null = unbekannt oder Erkennung aus). */
    val activeApp: String? = null,
    /** null = Erkennung ausgeschaltet, false = Berechtigung fehlt. */
    val appDetection: Boolean? = null,
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
