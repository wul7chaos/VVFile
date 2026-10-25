package com.vvfile.app.feature.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 音频面板 / 迷你条共用的播放状态。 */
data class AudioUiState(
    val connected: Boolean = false,
    val path: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val index: Int = 0,
    val count: Int = 0,
    val speed: Float = 1f,
    val repeatMode: Int = MediaFormats.REPEAT_ALL,
    val shuffle: Boolean = false,
    val queue: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * 音频播放的门面：连 [AudioPlaybackService] 里的 MediaSession，向界面暴露 [state]。
 *
 * 播放器本体在服务里，所以退到后台继续播、通知栏能控制；
 * 界面只负责发指令（播放/暂停/切歌/跳转/倍速）。
 */
object AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AudioUiState())
    val state: StateFlow<AudioUiState> = _state.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pending: Pair<List<String>, Int>? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(error = describe(error), playing = false, buffering = false)
            }
        }
    }

    fun connect(context: Context) {
        if (future != null || controller != null) return
        val app = context.applicationContext
        val token = SessionToken(app, ComponentName(app, AudioPlaybackService::class.java))
        val created = MediaController.Builder(app, token).buildAsync()
        future = created
        created.addListener(
            {
                val connected = runCatching { created.get() }.getOrNull()
                if (connected == null) {
                    // 服务没起来（例如被系统杀掉），清掉句柄，下次点播放再连
                    future = null
                    _state.update { it.copy(connected = false) }
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                _state.update { it.copy(connected = true) }
                pending?.let { (playlist, index) ->
                    pending = null
                    start(connected, playlist, index)
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    /** 点文件直接播：同目录同类型自动排成列表。 */
    fun play(context: Context, path: String) {
        val playlist = MediaLibrary.playlist(path)
        play(context, playlist, MediaFormats.indexOf(playlist, path))
    }

    fun play(context: Context, playlist: List<String>, startIndex: Int) {
        if (playlist.isEmpty()) return
        val ready = controller
        if (ready == null) {
            pending = playlist to startIndex
            connect(context)
            return
        }
        start(ready, playlist, startIndex)
    }

    fun togglePlayPause() {
        val player = controller ?: return
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_ENDED -> {
                player.seekToDefaultPosition()
                player.play()
            }
            else -> player.play()
        }
    }

    fun next() {
        controller?.run {
            if (hasNextMediaItem()) seekToNextMediaItem() else seekToDefaultPosition()
        }
    }

    fun previous() {
        controller?.run {
            if (hasPreviousMediaItem()) seekToPreviousMediaItem() else seekToDefaultPosition()
        }
    }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        controller?.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeatMode) {
            MediaFormats.REPEAT_OFF -> MediaFormats.REPEAT_ALL
            MediaFormats.REPEAT_ONE -> MediaFormats.REPEAT_OFF
            else -> MediaFormats.REPEAT_ONE
        }
        controller?.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        _state.update { it.copy(shuffle = player.shuffleModeEnabled) }
    }

    fun jumpTo(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        player.play()
    }

    /** 关闭面板：停播并清空队列，通知随之消失。 */
    fun stop() {
        controller?.run {
            stop()
            clearMediaItems()
        }
        pending = null
        ticker?.cancel()
        ticker = null
        val kept = _state.value
        _state.value = AudioUiState(
            connected = kept.connected,
            repeatMode = kept.repeatMode,
            speed = kept.speed,
            shuffle = kept.shuffle,
        )
    }

    private fun start(player: MediaController, playlist: List<String>, startIndex: Int) {
        val items = playlist.map { path ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(path)))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(MediaFormats.nameOf(path))
                        .setArtist(MediaFormats.parentOf(path))
                        .build(),
                )
                .build()
        }
        val previous = _state.value
        player.repeatMode = previous.repeatMode
        player.shuffleModeEnabled = previous.shuffle
        player.setPlaybackSpeed(previous.speed)
        player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        player.prepare()
        player.play()
        _state.update {
            it.copy(
                error = null,
                queue = playlist,
                count = playlist.size,
                index = startIndex.coerceIn(0, items.lastIndex),
            )
        }
        sync(player)
    }

    private fun sync(player: Player) {
        val duration = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
        val item = player.currentMediaItem
        val path = item?.localConfiguration?.uri?.path
            ?: _state.value.queue.getOrNull(player.currentMediaItemIndex)
        val title = item?.mediaMetadata?.title?.toString().orEmpty()
            .ifEmpty { path?.let(MediaFormats::nameOf).orEmpty() }
        val subtitle = item?.mediaMetadata?.artist?.toString().orEmpty()
        _state.update {
            it.copy(
                path = path,
                title = title,
                subtitle = subtitle,
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                ended = player.playbackState == Player.STATE_ENDED,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                index = player.currentMediaItemIndex.coerceAtLeast(0),
                count = player.mediaItemCount,
                speed = player.playbackParameters.speed,
                repeatMode = player.repeatMode,
                shuffle = player.shuffleModeEnabled,
            )
        }
        syncTicker()
    }

    /** 播放中每 500 ms 刷新一次进度；暂停/结束时停掉，避免空转。 */
    private fun syncTicker() {
        val playing = _state.value.playing
        if (!playing) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val player = controller ?: break
                if (!player.isPlaying) continue
                _state.update { it.copy(positionMs = player.currentPosition.coerceAtLeast(0L)) }
            }
        }
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件不存在或已被移动"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "没有读取该文件的权限"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "解码失败，格式不受支持"
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> "读取失败，文件可能已损坏"
        else -> "无法播放（${error.errorCodeName}）"
    }
}
