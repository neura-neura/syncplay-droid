package dev.neura.syncplay.player

import android.os.SystemClock
import dev.neura.syncplay.player.mpv.MpvEventOrigin
import dev.neura.syncplay.player.mpv.MpvPlaybackEvent
import dev.neura.syncplay.player.mpv.MpvPlaybackPhase
import dev.neura.syncplay.player.mpv.MpvPlaybackSession
import dev.neura.syncplay.player.mpv.MpvPlaybackSnapshot
import dev.neura.syncplay.protocol.LocalPlaybackState
import dev.neura.syncplay.protocol.RemotePlaybackState
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val MILLIS_PER_SECOND = 1_000.0
private const val MIN_PLAYBACK_SPEED = 0.25f
private const val MAX_PLAYBACK_SPEED = 4f
private const val SPEED_EPSILON = 0.001f

data class SynchronizerConfig(
    val hardSeekThresholdMs: Long = 1_500L,
    val driftToleranceMs: Long = 100L,
    val maxSpeedAdjustment: Float = 0.05f,
    val speedGainPerSecond: Float = 0.02f,
    val remoteEchoWindowMs: Long = 750L,
    val hardSeekCooldownMs: Long = 5_000L,
    val echoPositionToleranceMs: Long = 350L,
    val progressIntervalMs: Long = 250L,
    val remoteStaleAfterMs: Long = 5_000L,
    val maxMessageAgeSeconds: Double = 5.0,
    val nowElapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    init {
        require(hardSeekThresholdMs >= 0L)
        require(driftToleranceMs >= 0L)
        require(maxSpeedAdjustment.isFinite() && maxSpeedAdjustment >= 0f)
        require(speedGainPerSecond.isFinite() && speedGainPerSecond >= 0f)
        require(remoteEchoWindowMs >= 0L)
        require(hardSeekCooldownMs >= 0L)
        require(echoPositionToleranceMs >= 0L)
        require(progressIntervalMs > 0L)
        require(remoteStaleAfterMs > 0L)
        require(maxMessageAgeSeconds.isFinite() && maxMessageAgeSeconds >= 0.0)
    }
}

data class PlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val phase: MpvPlaybackPhase,
    val mediaIdentity: String?,
) {
    val positionSeconds: Double get() = positionMs / MILLIS_PER_SECOND
    val durationSeconds: Double? get() = durationMs.takeIf { it >= 0L }?.div(MILLIS_PER_SECOND)
}

internal fun isEffectivelyPaused(
    playWhenReady: Boolean,
    phase: MpvPlaybackPhase,
    hasPlayerError: Boolean,
): Boolean = !playWhenReady || hasPlayerError || phase in setOf(
    MpvPlaybackPhase.IDLE,
    MpvPlaybackPhase.STOPPED,
    MpvPlaybackPhase.ENDED,
    MpvPlaybackPhase.ERROR,
)

enum class LocalPlaybackChangeReason { POSITION, PLAYBACK, MEDIA_ITEM, SPEED, OTHER }

internal fun canSeekForRemoteCorrection(
    phase: MpvPlaybackPhase,
    forceSeek: Boolean,
): Boolean = forceSeek || phase == MpvPlaybackPhase.PLAYING || phase == MpvPlaybackPhase.PAUSED

data class LocalPlaybackEvent(
    val state: LocalPlaybackState,
    val progress: PlaybackProgress,
    val doSeek: Boolean,
    val reason: LocalPlaybackChangeReason,
)

enum class DriftAction { NONE, SEEK, ADJUST_SPEED, RESET_SPEED }

data class DriftDecision(val action: DriftAction, val speed: Float? = null)

object DriftPolicy {
    fun decide(
        errorMs: Long,
        paused: Boolean,
        forceSeek: Boolean,
        baseSpeed: Float,
        currentSpeed: Float,
        config: SynchronizerConfig = SynchronizerConfig(),
    ): DriftDecision {
        val absoluteError = abs(errorMs)
        if (forceSeek || absoluteError >= config.hardSeekThresholdMs ||
            (paused && absoluteError > config.driftToleranceMs)
        ) {
            return DriftDecision(DriftAction.SEEK)
        }
        if (paused || absoluteError <= config.driftToleranceMs) {
            return DriftDecision(DriftAction.RESET_SPEED)
        }
        val relative = ((errorMs / 1_000f) * config.speedGainPerSecond)
            .coerceIn(-config.maxSpeedAdjustment, config.maxSpeedAdjustment)
        val speed = (baseSpeed * (1f + relative)).coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        return if (abs(speed - currentSpeed) <= SPEED_EPSILON) {
            DriftDecision(DriftAction.NONE, speed)
        } else {
            DriftDecision(DriftAction.ADJUST_SPEED, speed)
        }
    }
}

data class RemoteApplyResult(
    val generation: Long,
    val targetPositionMs: Long,
    val deferredUntilMedia: Boolean,
    val seekRequested: Boolean,
    val seekApplied: Boolean,
    val playbackStateApplied: Boolean,
    val driftErrorMs: Long,
    val correctionSpeed: Float?,
)

/** Synchronizes one MPV session with the Syncplay protocol without a playback-framework adapter. */
class PlaybackSynchronizer(
    scope: CoroutineScope? = null,
    val config: SynchronizerConfig = SynchronizerConfig(),
) : Closeable {
    private val ownsScope = scope == null
    private val synchronizerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _localState = MutableStateFlow<LocalPlaybackState?>(null)
    private val _progress = MutableStateFlow<PlaybackProgress?>(null)
    val localState: StateFlow<LocalPlaybackState?> = _localState.asStateFlow()
    val progress: StateFlow<PlaybackProgress?> = _progress.asStateFlow()

    var onLocalPlaybackEvent: ((LocalPlaybackEvent) -> Unit)? = null
    var onRemoteApplied: ((RemoteApplyResult) -> Unit)? = null

    private var session: MpvPlaybackSession? = null
    private var eventJob: Job? = null
    private var pendingRemoteState: RemotePlaybackState? = null
    private var remoteAnchor: RemoteAnchor? = null
    private var generation = 0L
    private var lastAutomaticSeekAtMs = Long.MIN_VALUE
    private var basePlaybackSpeed = 1f
    private var correctionSpeed: Float? = null
    private var closed = false

    private val progressJob = synchronizerScope.launch {
        while (isActive) {
            delay(config.progressIntervalMs)
            tick()
        }
    }

    fun attach(session: MpvPlaybackSession) {
        if (closed || this.session === session) return
        detach()
        this.session = session
        basePlaybackSpeed = session.snapshot.value.rate
        refresh(session.snapshot.value)
        eventJob = synchronizerScope.launch {
            session.events.collect(::handleEvent)
        }
        applyPendingRemoteIfReady()
    }

    fun detach() {
        eventJob?.cancel()
        eventJob = null
        session?.let(::resetCorrection)
        session = null
        _localState.value = null
        _progress.value = null
    }

    fun snapshot(): LocalPlaybackState? = _localState.value
    val currentSnapshot: LocalPlaybackState? get() = _localState.value
    val latestProgress: PlaybackProgress? get() = _progress.value

    fun applyRemote(state: RemotePlaybackState): RemoteApplyResult = applyRemoteState(state)

    fun applyRemoteState(state: RemotePlaybackState): RemoteApplyResult {
        val now = config.nowElapsedRealtimeMs()
        val currentGeneration = ++generation
        val requested = secondsToMillis(state.positionSeconds) ?: 0L
        val target = safeAdd(requested, messageAgeMillis(state)).coerceAtLeast(0L)
        val active = session
        val activeSnapshot = active?.snapshot?.value
        if (active == null || activeSnapshot == null || !active.hasMedia() ||
            activeSnapshot.phase !in setOf(MpvPlaybackPhase.PLAYING, MpvPlaybackPhase.PAUSED)
        ) {
            pendingRemoteState = state
            return RemoteApplyResult(
                generation = currentGeneration,
                targetPositionMs = target,
                deferredUntilMedia = true,
                seekRequested = state.doSeek,
                seekApplied = false,
                playbackStateApplied = false,
                driftErrorMs = 0L,
                correctionSpeed = null,
            ).also(::notifyRemoteApplied)
        }

        val snapshot = activeSnapshot
        val boundedTarget = clampToDuration(target, snapshot.durationMs)
        val errorMs = boundedTarget - snapshot.positionMs
        val decision = DriftPolicy.decide(
            errorMs = errorMs,
            paused = state.paused,
            forceSeek = state.doSeek,
            baseSpeed = basePlaybackSpeed,
            currentSpeed = snapshot.rate,
            config = config,
        )
        var seekApplied = false
        var appliedSpeed: Float? = null
        val seekAllowed = state.doSeek || automaticSeekAllowed(now)
        if (decision.action == DriftAction.SEEK && seekAllowed &&
            canSeekForRemoteCorrection(snapshot.phase, state.doSeek)
        ) {
            active.seekTo(boundedTarget, exact = state.doSeek, origin = MpvEventOrigin.REMOTE)
            seekApplied = true
            if (!state.doSeek) lastAutomaticSeekAtMs = now
            resetCorrection(active)
        } else if (decision.action == DriftAction.ADJUST_SPEED && !state.paused) {
            appliedSpeed = decision.speed?.also {
                correctionSpeed = it
                active.setRate(it, MpvEventOrigin.CORRECTION)
            }
        } else if (decision.action == DriftAction.RESET_SPEED) {
            resetCorrection(active)
        }

        if (state.paused) active.pause(MpvEventOrigin.REMOTE) else active.play(MpvEventOrigin.REMOTE)
        remoteAnchor = RemoteAnchor(boundedTarget, state.paused, now, currentGeneration)
        val result = RemoteApplyResult(
            generation = currentGeneration,
            targetPositionMs = boundedTarget,
            deferredUntilMedia = false,
            seekRequested = state.doSeek,
            seekApplied = seekApplied,
            playbackStateApplied = true,
            driftErrorMs = errorMs,
            correctionSpeed = appliedSpeed,
        )
        refresh(active.snapshot.value)
        notifyRemoteApplied(result)
        return result
    }

    override fun close() {
        if (closed) return
        closed = true
        detach()
        progressJob.cancel()
        if (ownsScope) synchronizerScope.cancel()
        pendingRemoteState = null
        remoteAnchor = null
    }

    private fun handleEvent(event: MpvPlaybackEvent) {
        refresh(event.snapshot)
        if (event.snapshot.phase == MpvPlaybackPhase.PLAYING ||
            event.snapshot.phase == MpvPlaybackPhase.PAUSED
        ) {
            applyPendingRemoteIfReady()
        }
        if (event.origin != MpvEventOrigin.USER) return
        val reason = when (event.kind) {
            MpvPlaybackEvent.Kind.SEEK_REQUESTED -> LocalPlaybackChangeReason.POSITION
            MpvPlaybackEvent.Kind.PLAY_REQUESTED,
            MpvPlaybackEvent.Kind.PAUSE_REQUESTED,
            MpvPlaybackEvent.Kind.STOP_REQUESTED,
            -> LocalPlaybackChangeReason.PLAYBACK
            MpvPlaybackEvent.Kind.MEDIA_REQUESTED,
            MpvPlaybackEvent.Kind.CLEAR_REQUESTED,
            -> LocalPlaybackChangeReason.MEDIA_ITEM
            MpvPlaybackEvent.Kind.RATE_CHANGED -> LocalPlaybackChangeReason.SPEED
            else -> return
        }
        if (reason == LocalPlaybackChangeReason.SPEED) {
            basePlaybackSpeed = event.snapshot.rate
            correctionSpeed = null
        } else {
            remoteAnchor = null
            resetCorrection(session)
        }
        publishLocal(reason == LocalPlaybackChangeReason.POSITION, reason)
    }

    private fun tick() {
        val active = session ?: return
        val snapshot = active.snapshot.value
        refresh(snapshot)
        val anchor = remoteAnchor ?: return
        val now = config.nowElapsedRealtimeMs()
        if (now - anchor.receivedAtMs >= config.remoteStaleAfterMs) {
            remoteAnchor = null
            resetCorrection(active)
            return
        }
        if (snapshot.phase != MpvPlaybackPhase.PLAYING && snapshot.phase != MpvPlaybackPhase.PAUSED) return
        val target = if (anchor.paused) anchor.targetPositionMs else {
            safeAdd(anchor.targetPositionMs, now - anchor.receivedAtMs)
        }
        val error = target - snapshot.positionMs
        val decision = DriftPolicy.decide(
            errorMs = error,
            paused = anchor.paused,
            forceSeek = false,
            baseSpeed = basePlaybackSpeed,
            currentSpeed = snapshot.rate,
            config = config,
        )
        when (decision.action) {
            DriftAction.SEEK -> if (automaticSeekAllowed(now)) {
                active.seekTo(clampToDuration(target, snapshot.durationMs), exact = false, origin = MpvEventOrigin.CORRECTION)
                lastAutomaticSeekAtMs = now
                resetCorrection(active)
            }
            DriftAction.ADJUST_SPEED -> decision.speed?.let {
                correctionSpeed = it
                active.setRate(it, MpvEventOrigin.CORRECTION)
            }
            DriftAction.RESET_SPEED -> resetCorrection(active)
            DriftAction.NONE -> Unit
        }
    }

    private fun refresh(snapshot: MpvPlaybackSnapshot) {
        val progress = snapshot.toProgress()
        _progress.value = progress
        _localState.value = if (snapshot.mediaIdentity == null) null else LocalPlaybackState(
            positionSeconds = progress.positionSeconds,
            paused = isEffectivelyPaused(snapshot.playWhenReady, snapshot.phase, snapshot.error != null),
        )
    }

    private fun publishLocal(doSeek: Boolean, reason: LocalPlaybackChangeReason) {
        val state = _localState.value ?: return
        val progress = _progress.value ?: return
        runCatching { onLocalPlaybackEvent?.invoke(LocalPlaybackEvent(state, progress, doSeek, reason)) }
    }

    private fun applyPendingRemoteIfReady() {
        val pending = pendingRemoteState ?: return
        val active = session ?: return
        if (!active.hasMedia()) return
        pendingRemoteState = null
        applyRemoteState(pending)
    }

    private fun resetCorrection(active: MpvPlaybackSession?) {
        correctionSpeed = null
        if (active != null && abs(active.snapshot.value.rate - basePlaybackSpeed) > SPEED_EPSILON) {
            active.setRate(basePlaybackSpeed, MpvEventOrigin.CORRECTION)
        }
    }

    private fun notifyRemoteApplied(result: RemoteApplyResult) {
        runCatching { onRemoteApplied?.invoke(result) }
    }

    private fun MpvPlaybackSnapshot.toProgress(): PlaybackProgress = PlaybackProgress(
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs.coerceAtLeast(0L),
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        phase = phase,
        mediaIdentity = mediaIdentity,
    )

    private fun automaticSeekAllowed(now: Long): Boolean =
        lastAutomaticSeekAtMs == Long.MIN_VALUE || now - lastAutomaticSeekAtMs >= config.hardSeekCooldownMs

    private fun clampToDuration(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0L) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    private fun secondsToMillis(seconds: Double): Long? {
        if (!seconds.isFinite()) return null
        val value = seconds * MILLIS_PER_SECOND
        return when {
            value <= 0.0 -> 0L
            value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> value.roundToLong()
        }
    }

    private fun messageAgeMillis(state: RemotePlaybackState): Long {
        if (state.paused) return 0L
        val seconds = state.messageAgeSeconds.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(config.maxMessageAgeSeconds) ?: return 0L
        return (seconds * MILLIS_PER_SECOND).roundToLong().coerceAtLeast(0L)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class RemoteAnchor(
        val targetPositionMs: Long,
        val paused: Boolean,
        val receivedAtMs: Long,
        val generation: Long,
    )
}
