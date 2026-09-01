package dev.neura.syncplay.player

import android.os.SystemClock
import androidx.media3.common.Player
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
private const val MIN_PLAYBACK_SPEED = 0.1f
private const val MAX_PLAYBACK_SPEED = 8.0f
private const val SPEED_EPSILON = 0.001f
        private const val INTERNAL_SPEED_MARKER_MS = 1_000L
        private const val TIME_UNSET = -9_223_372_036_854_775_807L

/**
 * Tuning knobs for [PlaybackSynchronizer].
 *
 * Positions are kept in milliseconds internally.  The Syncplay protocol uses decimal seconds,
 * so conversion only happens while creating [LocalPlaybackState] or applying a remote state.
 */
data class SynchronizerConfig(
    /** A discontinuity is used once the error reaches this value. */
    val hardSeekThresholdMs: Long = 1_500L,
    /** Errors inside this window are considered in sync and restore the normal speed. */
    val driftToleranceMs: Long = 100L,
    /** Maximum relative speed correction (5% by default). */
    val maxSpeedAdjustment: Float = 0.05f,
    /** Relative speed correction per second of error. */
    val speedGainPerSecond: Float = 0.02f,
    /** Time during which callbacks caused by a remote command are not sent back as local input. */
    val remoteEchoWindowMs: Long = 750L,
    /** Minimum spacing between automatic (non-explicit) seeks while a stream is unstable. */
    val hardSeekCooldownMs: Long = 1_000L,
    /** Position tolerance used when matching an asynchronous remote callback to its generation. */
    val echoPositionToleranceMs: Long = 350L,
    /** Polling cadence for progress and continued drift correction. */
    val progressIntervalMs: Long = 250L,
    /** Stop correcting when no remote state has arrived for this long. */
    val remoteStaleAfterMs: Long = 5_000L,
    /** Never project a malformed or very old message by more than this amount. */
    val maxMessageAgeSeconds: Double = 5.0,
    /** Injectable monotonic clock, useful for deterministic policy tests. */
    val nowElapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    init {
        require(hardSeekThresholdMs >= 0L) { "hardSeekThresholdMs must be non-negative" }
        require(driftToleranceMs >= 0L) { "driftToleranceMs must be non-negative" }
        require(maxSpeedAdjustment >= 0f && maxSpeedAdjustment.isFinite()) {
            "maxSpeedAdjustment must be finite and non-negative"
        }
        require(speedGainPerSecond >= 0f && speedGainPerSecond.isFinite()) {
            "speedGainPerSecond must be finite and non-negative"
        }
        require(remoteEchoWindowMs >= 0L) { "remoteEchoWindowMs must be non-negative" }
        require(hardSeekCooldownMs >= 0L) { "hardSeekCooldownMs must be non-negative" }
        require(echoPositionToleranceMs >= 0L) { "echoPositionToleranceMs must be non-negative" }
        require(progressIntervalMs > 0L) { "progressIntervalMs must be positive" }
        require(remoteStaleAfterMs > 0L) { "remoteStaleAfterMs must be positive" }
        require(maxMessageAgeSeconds >= 0.0 && maxMessageAgeSeconds.isFinite()) {
            "maxMessageAgeSeconds must be finite and non-negative"
        }
    }
}

/** A point-in-time view of Media3 playback, suitable for UI progress and diagnostics. */
data class PlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val isPlaying: Boolean,
    val playWhenReady: Boolean,
    val playbackState: Int,
    val mediaItemIndex: Int,
    /** Media3 identity used to reject a late snapshot from a replaced item. */
    val mediaIdentity: String?,
) {
    val positionSeconds: Double
        get() = positionMs / MILLIS_PER_SECOND

    val durationSeconds: Double?
        get() = durationMs.takeUnless { it == TIME_UNSET || it < 0L }?.div(MILLIS_PER_SECOND)
}

/** Buffering preserves play intent; idle, ended and fatal-error states cannot be playing. */
internal fun isEffectivelyPaused(
    playWhenReady: Boolean,
    playbackState: Int,
    hasPlayerError: Boolean,
): Boolean = !playWhenReady || hasPlayerError ||
    playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED

/** Why a local Player event was reported to the protocol layer. */
enum class LocalPlaybackChangeReason {
    POSITION,
    PLAYBACK,
    MEDIA_ITEM,
    SPEED,
    OTHER,
}

/**
 * Classify a coalesced Media3 event batch without letting a timeline update hide a play/pause
 * intent.  Media3 may report [playWhenReadyChanged] together with a timeline/media transition in
 * one [Player.Events] callback.  A playWhenReady change is a local playback command when the
 * current media identity is unchanged; a changed identity remains a media-item event so loading a
 * new item does not send a spurious playstate to Syncplay.
 *
 * This helper deliberately accepts only primitive flags so the precedence can be tested on the
 * JVM without constructing a Media3 Player.
 */
internal fun classifyLocalPlaybackChange(
    doSeek: Boolean,
    mediaIdentityChanged: Boolean,
    mediaItemTransition: Boolean,
    timelineChanged: Boolean,
    playbackParametersChanged: Boolean,
    playWhenReadyChanged: Boolean,
): LocalPlaybackChangeReason = when {
    doSeek -> LocalPlaybackChangeReason.POSITION
    playWhenReadyChanged && !mediaIdentityChanged -> LocalPlaybackChangeReason.PLAYBACK
    mediaIdentityChanged || mediaItemTransition || timelineChanged ->
        LocalPlaybackChangeReason.MEDIA_ITEM
    playbackParametersChanged -> LocalPlaybackChangeReason.SPEED
    else -> LocalPlaybackChangeReason.OTHER
}

/** Event emitted for local changes; [doSeek] is true for a Media3 position discontinuity. */
data class LocalPlaybackEvent(
    val state: LocalPlaybackState,
    val progress: PlaybackProgress,
    val doSeek: Boolean,
    val reason: LocalPlaybackChangeReason,
)

/** Action selected by [DriftPolicy].  This type is deliberately free of Android/Media3 classes. */
enum class DriftAction {
    NONE,
    SEEK,
    ADJUST_SPEED,
    RESET_SPEED,
}

data class DriftDecision(
    val action: DriftAction,
    val speed: Float? = null,
)

/**
 * Pure, hysteretic drift policy.  It can be unit-tested with plain JVM tests and is also used by
 * the Media3 adapter below.
 */
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
        // A paused peer has no playback-rate mechanism with which to converge.  Seek whenever its
        // anchor is outside the tolerance, even if it is below the normal playing hard-seek
        // threshold; otherwise a paused room could remain permanently offset.
        if (forceSeek || absoluteError >= config.hardSeekThresholdMs ||
            (paused && absoluteError > config.driftToleranceMs)
        ) {
            return DriftDecision(DriftAction.SEEK)
        }
        if (paused || absoluteError <= config.driftToleranceMs) {
            return DriftDecision(DriftAction.RESET_SPEED)
        }

        val relativeAdjustment = ((errorMs.toFloat() / 1_000f) * config.speedGainPerSecond)
            .coerceIn(-config.maxSpeedAdjustment, config.maxSpeedAdjustment)
        val requestedSpeed = (baseSpeed * (1f + relativeAdjustment))
            .coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        return if (abs(requestedSpeed - currentSpeed) <= SPEED_EPSILON) {
            DriftDecision(DriftAction.NONE, requestedSpeed)
        } else {
            DriftDecision(DriftAction.ADJUST_SPEED, requestedSpeed)
        }
    }
}

/** Result of applying a remote Syncplay state. */
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

/**
 * Bridges a Media3 [Player] (including a [androidx.media3.session.MediaController]) and the
 * Syncplay state model.
 *
 * The service remains the owner of the player.  This class only calls Player commands, observes
 * Player events, and exposes StateFlows.  Call [close] before releasing the player; [close] never
 * releases the Player itself.  Calls should be made on the Player's application looper, as with
 * any ExoPlayer command.  A MediaController may be passed directly because it implements Player.
 */
class PlaybackSynchronizer(
    player: Player? = null,
    scope: CoroutineScope? = null,
    val config: SynchronizerConfig = SynchronizerConfig(),
    onLocalStateChanged: ((LocalPlaybackState, Boolean) -> Unit)? = null,
    onLocalStateChange: ((LocalPlaybackState, Boolean) -> Unit)? = null,
) : Closeable {
    private val ownsScope = scope == null
    private val synchronizerScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _localState = MutableStateFlow<LocalPlaybackState?>(null)
    private val _progress = MutableStateFlow<PlaybackProgress?>(null)

    /** Latest local state; null means no current MediaItem is available. */
    val localState: StateFlow<LocalPlaybackState?> = _localState.asStateFlow()

    /** Latest progress snapshot; null means no player is attached. */
    val progress: StateFlow<PlaybackProgress?> = _progress.asStateFlow()

    /** Called for local changes after remote-echo suppression has been applied. */
    var onLocalStateChanged: ((LocalPlaybackState, Boolean) -> Unit)? =
        onLocalStateChanged ?: onLocalStateChange

    /** Alias with the singular spelling used by some ViewModels. */
    var onLocalStateChange: ((LocalPlaybackState, Boolean) -> Unit)? = null

    /** Rich local callback including reason and progress. */
    var onLocalPlaybackEvent: ((LocalPlaybackEvent) -> Unit)? = null

    /** Optional observer for diagnostics (network code normally only needs the return value). */
    var onRemoteApplied: ((RemoteApplyResult) -> Unit)? = null

    private var attachedPlayer: Player? = null
    private var closed = false
    private var pendingRemoteState: RemotePlaybackState? = null
    private var remoteAnchor: RemoteAnchor? = null
    private var remoteGeneration = 0L
    private var suppressOutgoingUntilMs = 0L
    private var applyingRemoteDepth = 0
    private var lastAutomaticSeekAtMs = Long.MIN_VALUE

    private var basePlaybackSpeed = 1f
    private var correctionSpeed: Float? = null
    private var expectedInternalSpeed: Float? = null
    private var expectedInternalSpeedUntilMs = 0L
    private var lastPositionDiscontinuityReason = Int.MIN_VALUE
    /** Media identity observed by the previous Player event batch. */
    private var lastObservedMediaIdentity: String? = null

    private val playerListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            lastPositionDiscontinuityReason = reason
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (closed || player !== attachedPlayer) return

            val now = config.nowElapsedRealtimeMs()
            if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                observePlaybackSpeed(player, now)
            }

            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                applyPendingRemoteIfReady()
            }

            // `onEvents` is coalesced per Looper iteration.  Read the identity after applying a
            // deferred remote state because that operation may synchronously trigger nested Player
            // callbacks.  The identity guard lets a real play/pause toggle win over a timeline
            // flag when the same media item is still selected.
            val mediaIdentity = currentMediaIdentity(player)
            val mediaIdentityChanged = mediaIdentity != lastObservedMediaIdentity
            lastObservedMediaIdentity = mediaIdentity

            val playbackEvent = events.containsAny(
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
            )
            if (playbackEvent) {
                val hasDiscontinuity = events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                val doSeek = hasDiscontinuity && isUserSeekDiscontinuity(events)
                if (hasDiscontinuity) lastPositionDiscontinuityReason = Int.MIN_VALUE
                val reason = classifyLocalPlaybackChange(
                    doSeek = doSeek,
                    mediaIdentityChanged = mediaIdentityChanged,
                    mediaItemTransition = events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION),
                    timelineChanged = events.contains(Player.EVENT_TIMELINE_CHANGED),
                    playbackParametersChanged =
                        events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED),
                    playWhenReadyChanged = events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED),
                )
                publishLocalEvent(doSeek = doSeek, reason = reason)
            }
        }
    }

    private val progressJob: Job = synchronizerScope.launch {
        while (isActive && !closed) {
            tick()
            delay(config.progressIntervalMs)
        }
    }

    init {
        player?.let(::attach)
    }

    /** Attach to a Player or MediaController.  Attaching replaces any previous Player. */
    fun attach(player: Player) {
        check(!closed) { "PlaybackSynchronizer is closed" }
        if (attachedPlayer === player) {
            refreshFlows()
            applyPendingRemoteIfReady()
            return
        }
        val previousPlayer = attachedPlayer
        previousPlayer?.removeListener(playerListener)
        resetCorrection(previousPlayer)
        attachedPlayer = player
        // A correction anchor belongs to the previous Player.  Keep only an explicitly deferred
        // remote state, which will be replayed once this Player exposes a MediaItem.
        remoteAnchor = null
        suppressOutgoingUntilMs = 0L
        lastAutomaticSeekAtMs = Long.MIN_VALUE
        basePlaybackSpeed = safePlaybackSpeed(player)
        correctionSpeed = null
        expectedInternalSpeed = null
        lastPositionDiscontinuityReason = Int.MIN_VALUE
        lastObservedMediaIdentity = currentMediaIdentity(player)
        player.addListener(playerListener)
        refreshFlows()
        applyPendingRemoteIfReady()
    }

    /** Detach without releasing the Player; useful when a MediaController is recreated. */
    fun detach() {
        val previousPlayer = attachedPlayer
        previousPlayer?.removeListener(playerListener)
        resetCorrection(previousPlayer)
        attachedPlayer = null
        remoteAnchor = null
        suppressOutgoingUntilMs = 0L
        lastAutomaticSeekAtMs = Long.MIN_VALUE
        lastPositionDiscontinuityReason = Int.MIN_VALUE
        resetCorrection(null)
        _localState.value = null
        _progress.value = null
        lastObservedMediaIdentity = null
    }

    /**
     * Return the latest local protocol state, or null when no MediaItem is loaded.  The value is
     * read from the atomically updated StateFlow rather than touching ExoPlayer from a protocol
     * IO thread; this is safe for [dev.neura.syncplay.protocol.SyncplayConnection] providers.
     */
    fun snapshot(): LocalPlaybackState? = _localState.value

    /** Apply a state received from Syncplay; alias retained for concise call sites. */
    fun applyRemote(state: RemotePlaybackState): RemoteApplyResult = applyRemoteState(state)

    /**
     * Apply a remote state using projection, hard seek for large errors, and speed nudging for
     * small errors.  A remote command receives a generation and echo-suppression window so the
     * asynchronous Player callbacks do not immediately get sent back as local commands.
     */
    fun applyRemoteState(state: RemotePlaybackState): RemoteApplyResult {
        if (closed) {
            return RemoteApplyResult(
                generation = remoteGeneration,
                targetPositionMs = 0L,
                deferredUntilMedia = true,
                seekRequested = state.doSeek,
                seekApplied = false,
                playbackStateApplied = false,
                driftErrorMs = 0L,
                correctionSpeed = null,
            )
        }

        val now = config.nowElapsedRealtimeMs()
        val generation = ++remoteGeneration
        val player = attachedPlayer
        val requestedPositionMs = secondsToMillis(state.positionSeconds)
        // A playstate is sampled in the past.  Advance it by the age carried by Syncplay while
        // playing; paused states (including malformed/negative ages) remain exact anchors.
        val projectedPositionMs = requestedPositionMs?.let {
            safeAdd(it, messageAgeMillis(state))
        }
        val targetPositionMs = projectedPositionMs?.let { clampToDuration(player, it) }
            ?: safeCurrentPosition(player)

        if (player == null || !hasMedia(player)) {
            pendingRemoteState = state
            remoteAnchor = null
            resetCorrection(player)
            suppressOutgoingUntilMs = maxOf(suppressOutgoingUntilMs, now + config.remoteEchoWindowMs)
            val result = RemoteApplyResult(
                generation = generation,
                targetPositionMs = targetPositionMs,
                deferredUntilMedia = true,
                seekRequested = state.doSeek && requestedPositionMs != null,
                seekApplied = false,
                playbackStateApplied = false,
                driftErrorMs = 0L,
                correctionSpeed = null,
            )
            notifyRemoteApplied(result)
            return result
        }

        pendingRemoteState = null
        val anchor = RemoteAnchor(
            targetPositionMs = targetPositionMs,
            paused = state.paused,
            receivedAtMs = now,
            generation = generation,
        )
        remoteAnchor = anchor
        suppressOutgoingUntilMs = maxOf(suppressOutgoingUntilMs, now + config.remoteEchoWindowMs)

        val localPositionMs = safeCurrentPosition(player)
        val errorMs = targetPositionMs - localPositionMs
        val forceSeek = state.doSeek && requestedPositionMs != null
        var seekApplied = false
        var playbackStateApplied = false
        var appliedCorrectionSpeed: Float? = null

        applyingRemoteDepth++
        try {
            val decision = DriftPolicy.decide(
                errorMs = errorMs,
                paused = state.paused,
                forceSeek = forceSeek,
                baseSpeed = basePlaybackSpeed,
                currentSpeed = safePlaybackSpeed(player),
                config = config,
            )

            when (decision.action) {
                DriftAction.SEEK -> {
                    // A paused peer cannot converge through playback-rate nudging, so its anchor
                    // must bypass the playing-state seek cooldown.  Explicit seeks likewise
                    // always win; the cooldown only throttles repeated automatic seeks while
                    // both sides are actively playing.
                    val seekAllowed = forceSeek || state.paused || automaticSeekAllowed(now)
                    seekApplied = seekAllowed && seekTo(player, targetPositionMs)
                    if (seekApplied && !forceSeek) lastAutomaticSeekAtMs = now
                    if (seekApplied || state.paused) {
                        resetCorrection(player)
                    } else {
                        // A MediaController can temporarily deny seek.  Nudge speed instead of
                        // abandoning convergence while waiting for a later state update.
                        appliedCorrectionSpeed = applySpeedCorrection(player, errorMs, now)
                    }
                }

                DriftAction.ADJUST_SPEED -> {
                    appliedCorrectionSpeed = decision.speed?.let {
                        setCorrectionSpeed(player, it, now)
                    }
                }

                DriftAction.RESET_SPEED,
                DriftAction.NONE,
                -> {
                    if (decision.action == DriftAction.RESET_SPEED) {
                        resetCorrection(player)
                    } else {
                        appliedCorrectionSpeed = correctionSpeed
                    }
                }
            }

            playbackStateApplied = if (state.paused) {
                resetCorrection(player)
                ensurePaused(player)
            } else {
                ensurePlaying(player)
            }
        } finally {
            applyingRemoteDepth--
        }

        // Player commands are synchronous on the application looper. Refresh immediately so the
        // protocol acknowledgement can report what Media3 actually accepted.
        refreshFlows()

        val result = RemoteApplyResult(
            generation = generation,
            targetPositionMs = targetPositionMs,
            deferredUntilMedia = false,
            seekRequested = forceSeek || abs(errorMs) >= config.hardSeekThresholdMs ||
                (state.paused && abs(errorMs) > config.driftToleranceMs),
            seekApplied = seekApplied,
            playbackStateApplied = playbackStateApplied,
            driftErrorMs = errorMs,
            correctionSpeed = appliedCorrectionSpeed,
        )
        notifyRemoteApplied(result)
        return result
    }

    /** Last generation assigned to a remote state. */
    val lastRemoteGeneration: Long
        get() = remoteGeneration

    /** Synchronous alias for callers that prefer a property over [snapshot]. */
    val currentSnapshot: LocalPlaybackState?
        get() = _localState.value

    /** Synchronous alias for the latest progress flow value. */
    val latestProgress: PlaybackProgress?
        get() = _progress.value

    override fun close() {
        if (closed) return
        closed = true
        val previousPlayer = attachedPlayer
        previousPlayer?.removeListener(playerListener)
        resetCorrection(previousPlayer)
        attachedPlayer = null
        progressJob.cancel()
        if (ownsScope) synchronizerScope.cancel()
        pendingRemoteState = null
        remoteAnchor = null
        _localState.value = null
        _progress.value = null
        lastObservedMediaIdentity = null
    }

    private fun tick() {
        val player = attachedPlayer ?: return
        val progress = runCatching { readProgress(player) }.getOrNull() ?: return
        val state = runCatching { readLocalState(player) }.getOrNull()
        _progress.value = progress
        _localState.value = state
        if (state == null) {
            remoteAnchor = null
            resetCorrection(player)
            return
        }

        val anchor = remoteAnchor ?: return
        val now = config.nowElapsedRealtimeMs()
        if (now - anchor.receivedAtMs >= config.remoteStaleAfterMs) {
            remoteAnchor = null
            resetCorrection(player)
            return
        }

        // Do not turn a local pause into an unsolicited play.  The next server state will make
        // the play/pause decision; while playing, however, continue convergence between updates.
        if (anchor.paused || !player.playWhenReady) {
            if (anchor.paused) resetCorrection(player)
            return
        }

        val target = projectedTarget(anchor, now)
        val errorMs = target - safeCurrentPosition(player)
        val decision = DriftPolicy.decide(
            errorMs = errorMs,
            paused = false,
            forceSeek = false,
            baseSpeed = basePlaybackSpeed,
            currentSpeed = safePlaybackSpeed(player),
            config = config,
        )
        when (decision.action) {
            DriftAction.SEEK -> {
                if (automaticSeekAllowed(now) && seekTo(player, target)) {
                    lastAutomaticSeekAtMs = now
                    suppressOutgoingUntilMs = maxOf(suppressOutgoingUntilMs, now + config.remoteEchoWindowMs)
                    resetCorrection(player)
                } else {
                    applySpeedCorrection(player, errorMs, now)
                }
            }

            DriftAction.ADJUST_SPEED -> decision.speed?.let { setCorrectionSpeed(player, it, now) }
            DriftAction.RESET_SPEED -> resetCorrection(player)
            DriftAction.NONE -> Unit
        }
    }

    private fun applyPendingRemoteIfReady() {
        val pending = pendingRemoteState ?: return
        val player = attachedPlayer ?: return
        if (!hasMedia(player)) return
        pendingRemoteState = null
        applyRemoteState(pending)
    }

    private fun publishLocalEvent(doSeek: Boolean, reason: LocalPlaybackChangeReason) {
        val player = attachedPlayer ?: return
        val state = runCatching { readLocalState(player) }.getOrNull()
        if (state == null) {
            _localState.value = null
            _progress.value = runCatching { readProgress(player) }.getOrNull()
            remoteAnchor = null
            resetCorrection(player)
            return
        }
        val progress = runCatching { readProgress(player) }.getOrNull() ?: return
        _localState.value = state
        _progress.value = progress

        val now = config.nowElapsedRealtimeMs()
        val suppressed = shouldSuppressRemoteEcho(state, now)
        if (suppressed || applyingRemoteDepth > 0) return

        // A genuine local event supersedes an old anchor.  This prevents the ticker from fighting
        // a user's seek/play/pause while the server is processing the outgoing command.
        remoteAnchor = null
        if (reason != LocalPlaybackChangeReason.SPEED || doSeek) {
            resetCorrection(player)
        }

        val event = LocalPlaybackEvent(state, progress, doSeek, reason)
        val primaryCallback = onLocalStateChanged
        val aliasCallback = onLocalStateChange
        runCatching { primaryCallback?.invoke(state, doSeek) }
        if (aliasCallback !== primaryCallback) {
            runCatching { aliasCallback?.invoke(state, doSeek) }
        }
        runCatching { onLocalPlaybackEvent?.invoke(event) }
    }

    private fun isUserSeekDiscontinuity(events: Player.Events): Boolean {
        return when (lastPositionDiscontinuityReason) {
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            Player.DISCONTINUITY_REASON_REMOVE,
            Player.DISCONTINUITY_REASON_INTERNAL,
            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
            Player.DISCONTINUITY_REASON_SILENCE_SKIP,
            -> false

            // If Media3 did not deliver the reason callback (some remote Player implementations
            // only emit onEvents), a discontinuity without an item transition is most likely a
            // seek.  This fallback keeps MediaController implementations interoperable.
            Int.MIN_VALUE -> !events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            else -> true
        }
    }

    private fun shouldSuppressRemoteEcho(state: LocalPlaybackState, now: Long): Boolean {
        if (applyingRemoteDepth > 0) return true
        if (now >= suppressOutgoingUntilMs) return false
        val anchor = remoteAnchor ?: return true
        val expectedPosition = projectedTarget(anchor, now)
        val actualPosition = secondsToMillis(state.positionSeconds) ?: return true
        return state.paused == anchor.paused &&
            abs(actualPosition - expectedPosition) <= config.echoPositionToleranceMs
    }

    private fun observePlaybackSpeed(player: Player, now: Long) {
        val observed = safePlaybackSpeed(player)
        // Keep the user-selected baseline stable even if a MediaController delivers the
        // playback-parameter callback after our marker timeout.  A value equal to the active
        // correction is ours; a different value is a genuine controller/user override.
        correctionSpeed?.let { correction ->
            if (abs(observed - correction) <= SPEED_EPSILON) {
                expectedInternalSpeed = null
                return
            }
        }
        val expected = expectedInternalSpeed
        if (expected != null && now <= expectedInternalSpeedUntilMs &&
            abs(observed - expected) <= SPEED_EPSILON
        ) {
            expectedInternalSpeed = null
            return
        }
        // A speed event not caused by our correction is a user/controller change.  Keep it as the
        // baseline so resetting a nudge never overwrites the user's selected playback rate.
        basePlaybackSpeed = observed
        correctionSpeed = null
        expectedInternalSpeed = null
    }

    private fun applySpeedCorrection(player: Player, errorMs: Long, now: Long): Float? {
        val relative = ((errorMs.toFloat() / 1_000f) * config.speedGainPerSecond)
            .coerceIn(-config.maxSpeedAdjustment, config.maxSpeedAdjustment)
        return setCorrectionSpeed(
            player,
            (basePlaybackSpeed * (1f + relative)).coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED),
            now,
        )
    }

    private fun setCorrectionSpeed(player: Player, speed: Float, now: Long): Float? {
        if (!commandAvailable(player, Player.COMMAND_SET_SPEED_AND_PITCH)) return null
        val bounded = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        val current = safePlaybackSpeed(player)
        correctionSpeed = bounded
        expectedInternalSpeed = bounded
        expectedInternalSpeedUntilMs = now + INTERNAL_SPEED_MARKER_MS
        if (abs(current - bounded) > SPEED_EPSILON) {
            runCatching { player.setPlaybackSpeed(bounded) }.getOrElse {
                correctionSpeed = null
                expectedInternalSpeed = null
                return null
            }
        }
        return bounded
    }

    private fun resetCorrection(player: Player?) {
        correctionSpeed = null
        val target = basePlaybackSpeed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        if (player == null || !commandAvailable(player, Player.COMMAND_SET_SPEED_AND_PITCH)) {
            expectedInternalSpeed = null
            return
        }
        val current = safePlaybackSpeed(player)
        if (abs(current - target) <= SPEED_EPSILON) {
            expectedInternalSpeed = null
            return
        }
        expectedInternalSpeed = target
        expectedInternalSpeedUntilMs = config.nowElapsedRealtimeMs() + INTERNAL_SPEED_MARKER_MS
        runCatching { player.setPlaybackSpeed(target) }
            .onFailure { expectedInternalSpeed = null }
    }

    private fun seekTo(player: Player, targetPositionMs: Long): Boolean {
        if (!commandAvailable(player, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return false
        val target = clampToDuration(player, targetPositionMs)
        return runCatching { player.seekTo(target) }.isSuccess
    }

    private fun automaticSeekAllowed(now: Long): Boolean =
        lastAutomaticSeekAtMs == Long.MIN_VALUE ||
            now - lastAutomaticSeekAtMs >= config.hardSeekCooldownMs

    private fun play(player: Player): Boolean {
        if (!commandAvailable(player, Player.COMMAND_PLAY_PAUSE)) return false
        return runCatching { player.play() }.isSuccess
    }

    private fun ensurePlaying(player: Player): Boolean =
        if (runCatching { player.playWhenReady }.getOrDefault(false)) true else play(player)

    private fun ensurePaused(player: Player): Boolean =
        if (!runCatching { player.playWhenReady }.getOrDefault(false)) true else pause(player)

    private fun pause(player: Player): Boolean {
        if (!commandAvailable(player, Player.COMMAND_PLAY_PAUSE)) return false
        return runCatching { player.pause() }.isSuccess
    }

    private fun commandAvailable(player: Player, command: Int): Boolean =
        runCatching { player.isCommandAvailable(command) }.getOrDefault(false)

    private fun notifyRemoteApplied(result: RemoteApplyResult) {
        runCatching { onRemoteApplied?.invoke(result) }
    }

    private fun refreshFlows() {
        val player = attachedPlayer
        if (player == null) {
            _localState.value = null
            _progress.value = null
            return
        }
        runCatching {
            _localState.value = readLocalState(player)
            _progress.value = readProgress(player)
        }
    }

    private fun hasMedia(player: Player): Boolean = runCatching {
        player.mediaItemCount > 0 && player.currentMediaItem != null
    }.getOrDefault(false)

    /** Stable identity used only to disambiguate coalesced media/timeline and play/pause events. */
    private fun currentMediaIdentity(player: Player?): String? = player?.let {
        runCatching {
            it.currentMediaItem?.let { item ->
                item.mediaId.takeIf(String::isNotEmpty)
                    ?: item.localConfiguration?.uri?.toString()
                    ?: "index:${it.currentMediaItemIndex}"
            }
        }.getOrNull()
    }

    private fun readLocalState(player: Player): LocalPlaybackState? {
        if (!hasMedia(player)) return null
        val position = safeCurrentPosition(player)
        // playWhenReady is the user's intent; buffering is not advertised as a Syncplay pause.
        val paused = isEffectivelyPaused(
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState,
            hasPlayerError = player.playerError != null,
        )
        return LocalPlaybackState(
            positionSeconds = position / MILLIS_PER_SECOND,
            paused = paused,
        )
    }

    private fun readProgress(player: Player): PlaybackProgress = PlaybackProgress(
        positionMs = safeCurrentPosition(player),
        durationMs = runCatching { player.duration }.getOrDefault(TIME_UNSET),
        bufferedPositionMs = runCatching { player.bufferedPosition }.getOrDefault(0L).coerceAtLeast(0L),
        isPlaying = runCatching { player.isPlaying }.getOrDefault(false),
        playWhenReady = runCatching { player.playWhenReady }.getOrDefault(false),
        playbackState = runCatching { player.playbackState }.getOrDefault(Player.STATE_IDLE),
        mediaItemIndex = runCatching { player.currentMediaItemIndex }.getOrDefault(-1),
        mediaIdentity = currentMediaIdentity(player),
    )

    private fun safeCurrentPosition(player: Player?): Long = player?.let {
        runCatching { it.currentPosition }.getOrDefault(0L).coerceAtLeast(0L)
    } ?: 0L

    private fun safePlaybackSpeed(player: Player): Float = runCatching {
        player.playbackParameters.speed
    }.getOrDefault(1f).coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)

    private fun clampToDuration(player: Player?, positionMs: Long): Long {
        val positive = positionMs.coerceAtLeast(0L)
        val duration = player?.let { runCatching { it.duration }.getOrDefault(TIME_UNSET) } ?: TIME_UNSET
        return if (duration != TIME_UNSET && duration > 0L) positive.coerceAtMost(duration) else positive
    }

    private fun projectedTarget(anchor: RemoteAnchor, now: Long): Long {
        if (anchor.paused) return anchor.targetPositionMs
        val elapsed = (now - anchor.receivedAtMs).coerceAtLeast(0L)
        return safeAdd(anchor.targetPositionMs, elapsed)
    }

    private fun secondsToMillis(seconds: Double): Long? {
        if (!seconds.isFinite()) return null
        val millis = seconds * MILLIS_PER_SECOND
        return when {
            millis <= 0.0 -> 0L
            millis >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> millis.roundToLong()
        }
    }

    private fun messageAgeMillis(state: RemotePlaybackState): Long {
        if (state.paused) return 0L
        val ageSeconds = state.messageAgeSeconds
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceAtMost(config.maxMessageAgeSeconds)
            ?: return 0L
        val ageMillis = ageSeconds * MILLIS_PER_SECOND
        return when {
            ageMillis >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
            else -> ageMillis.roundToLong().coerceAtLeast(0L)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private data class RemoteAnchor(
        val targetPositionMs: Long,
        val paused: Boolean,
        val receivedAtMs: Long,
        val generation: Long,
    )

}
