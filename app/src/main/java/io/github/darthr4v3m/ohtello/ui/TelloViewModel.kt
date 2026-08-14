package io.github.darthr4v3m.ohtello.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.darthr4v3m.ohtello.BuildConfig
import io.github.darthr4v3m.ohtello.tello.CommandLogEntry
import io.github.darthr4v3m.ohtello.tello.ConnectionState
import io.github.darthr4v3m.ohtello.tello.SessionLogStore
import io.github.darthr4v3m.ohtello.tello.TelloController
import io.github.darthr4v3m.ohtello.tello.WifiSocketBinder
import io.github.darthr4v3m.ohtello.tello.protocol.MoveDirection
import io.github.darthr4v3m.ohtello.tello.protocol.TelloCommands
import io.github.darthr4v3m.ohtello.tello.protocol.TelloState
import io.github.darthr4v3m.ohtello.tello.protocol.TurnDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class TelloViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionLog = SessionLogStore(File(application.filesDir, "logs"))

    private val notifier = FlightNotifier(application)

    private val controller = TelloController(
        socketBinder = WifiSocketBinder(application),
        sessionLog = sessionLog,
    )

    init {
        // Recorded per run of the app, and stamped with what produced it: a log
        // sent on later is worth little without the build and the device.
        sessionLog.startSession(
            "Oh-Tello ${BuildConfig.VERSION_NAME} — ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    /** The stored sessions as one blob, for the console's share button. */
    fun logsForSharing(): String = sessionLog.shareableText()

    val connection: StateFlow<ConnectionState> = controller.connection
    val state: StateFlow<TelloState?> = controller.state
    val telemetryFresh: StateFlow<Boolean> = controller.telemetryFresh
    val log: StateFlow<List<CommandLogEntry>> = controller.log

    /** Raised when the pilot has sent nothing for nearly the drone's failsafe window. */
    val pilotIdle: StateFlow<Boolean> = controller.pilotIdle

    /** True while a command is in flight, so the UI can grey out the D-pad. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _stepCm = MutableStateFlow(DEFAULT_STEP_CM)
    val stepCm: StateFlow<Int> = _stepCm.asStateFlow()

    private val _turnDegrees = MutableStateFlow(DEFAULT_TURN_DEGREES)
    val turnDegrees: StateFlow<Int> = _turnDegrees.asStateFlow()

    private val _showKeepAlives = MutableStateFlow(false)
    val showKeepAlives: StateFlow<Boolean> = _showKeepAlives.asStateFlow()

    /** Emergency needs a second tap to fire; this is the armed window. */
    private val _emergencyArmed = MutableStateFlow(false)
    val emergencyArmed: StateFlow<Boolean> = _emergencyArmed.asStateFlow()

    private var disarmJob: Job? = null
    private var warnJob: Job? = null

    fun connect() = runExclusively { controller.connect() }

    fun disconnect() {
        controller.disconnect()
    }

    fun takeoff() = runExclusively { controller.takeoff() }

    /**
     * Land always works, and never through [runExclusively].
     *
     * It used to jump the queue only when `busy` was set, but `busy` is set by
     * this class and knows nothing about the controller's own keepalive — one of
     * those stuck in a five second timeout holds the queue with `busy` false, and
     * Land would have waited behind it.
     */
    fun land() {
        viewModelScope.launch { controller.land() }
    }

    /**
     * Called as the app comes and goes; see TelloController.setOperatorPresent.
     *
     * Backgrounding stops the keepalive, so the drone lands itself about ten
     * seconds later. If it is airborne when that happens, say so — and it has to
     * be a notification, because by definition nobody is looking at the screen.
     */
    fun setOperatorPresent(present: Boolean) {
        controller.setOperatorPresent(present)

        warnJob?.cancel()
        if (present) {
            notifier.clear()
            return
        }
        if (controller.connection.value !is ConnectionState.Connected) return
        // Height reads 0 on the ground, so a drone sitting there earns no
        // warning. Unknown height does: better a needless nudge than silence.
        val height = controller.state.value?.heightCm
        if (height != null && height <= 0) return

        warnJob = viewModelScope.launch {
            delay(BACKGROUND_WARNING_DELAY_MS)
            notifier.warnDroneWillLand()
        }
    }

    /** Land, then let the caller finish the activity — used by the back gesture. */
    fun landThen(onDone: () -> Unit) {
        viewModelScope.launch {
            controller.land()
            onDone()
        }
    }

    /**
     * First tap arms, second tap within [EMERGENCY_ARM_WINDOW_MS] cuts the
     * motors. Worth the extra tap: this drops the drone out of the sky, and it
     * sits next to the buttons you press while flying.
     */
    fun emergency() {
        if (!_emergencyArmed.value) {
            _emergencyArmed.value = true
            disarmJob?.cancel()
            disarmJob = viewModelScope.launch {
                delay(EMERGENCY_ARM_WINDOW_MS)
                _emergencyArmed.value = false
            }
            return
        }
        disarmJob?.cancel()
        _emergencyArmed.value = false
        viewModelScope.launch { controller.emergency() }
    }

    fun move(direction: MoveDirection) = runExclusively { controller.move(direction, _stepCm.value) }

    fun turn(direction: TurnDirection) =
        runExclusively { controller.turn(direction, _turnDegrees.value) }

    fun queryBattery() = runExclusively { controller.sendCommand(TelloCommands.QUERY_BATTERY) }

    fun setStepCm(centimetres: Int) {
        _stepCm.value = centimetres.coerceIn(
            TelloCommands.DISTANCE_MIN_CM,
            TelloCommands.DISTANCE_MAX_CM,
        )
    }

    fun setTurnDegrees(degrees: Int) {
        _turnDegrees.value = degrees.coerceIn(
            TelloCommands.ANGLE_MIN_DEG,
            TelloCommands.ANGLE_MAX_DEG,
        )
    }

    fun setShowKeepAlives(show: Boolean) {
        _showKeepAlives.value = show
    }

    fun clearLog() = controller.clearLog()

    /**
     * Runs one command at a time. Button mashing while the drone is mid-move
     * would otherwise pile up commands that all execute later, in order, long
     * after the pilot stopped meaning them.
     */
    private fun runExclusively(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    /** True once the app may post the background warning. */
    fun canNotify(): Boolean = notifier.canNotify()

    override fun onCleared() {
        notifier.clear()
        controller.disconnect()
        super.onCleared()
    }

    companion object {
        const val DEFAULT_STEP_CM = 30
        const val DEFAULT_TURN_DEGREES = 45
        private const val EMERGENCY_ARM_WINDOW_MS = 3_000L

        /**
         * Long enough that flicking to another app and straight back does not
         * fire it, short enough to still be a warning: the drone lands about ten
         * seconds after the app goes away.
         */
        private const val BACKGROUND_WARNING_DELAY_MS = 2_000L

        val STEP_OPTIONS_CM = listOf(20, 30, 50, 100)
        val TURN_OPTIONS_DEGREES = listOf(30, 45, 90, 180)
    }
}
