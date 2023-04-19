package com.spectre7.spmp.platform

import com.spectre7.spmp.model.Song
import kotlinx.coroutines.CoroutineScope

internal const val AUTO_DOWNLOAD_SOFT_TIMEOUT = 1500 // ms

enum class MediaPlayerState {
    IDLE,
    BUFFERING,
    READY,
    ENDED
}

enum class MediaPlayerRepeatMode {
    OFF, 
    ONE, 
    ALL
}

expect open class MediaPlayerService(): PlatformService {
    open class Listener() {
        open fun onSongTransition(song: Song?)
        open fun onStateChanged(state: MediaPlayerState)
        open fun onPlayingChanged(is_playing: Boolean)
        open fun onRepeatModeChanged(repeat_mode: MediaPlayerRepeatMode)
        open fun onVolumeChanged(volume: Float)
        open fun onSeeked(position_ms: Long) {}

        open fun onSongAdded(index: Int, song: Song?) {}
        open fun onSongRemoved(index: Int) {}
        open fun onSongMoved(from: Int, to: Int) {}

        open fun onEvents()
    }

    var session_started: Boolean
        private set

    val state: MediaPlayerState
    val is_playing: Boolean
    val song_count: Int
    val current_song_index: Int
    val current_position_ms: Long
    val duration_ms: Long

    var repeat_mode: MediaPlayerRepeatMode
    var volume: Float

    val has_focus: Boolean

    open fun play()
    open fun pause()
    open fun playPause()

    open fun seekTo(position_ms: Long)
    open fun seekTo(index: Int, position_ms: Long = 0)
    open fun seekToNext()
    open fun seekToPrevious()

    fun getSong(): Song?
    fun getSong(index: Int): Song?

    fun addSong(song: Song)
    fun addSong(song: Song, index: Int)
    fun moveSong(from: Int, to: Int)
    fun removeSong(index: Int)

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    companion object {
        fun CoroutineScope.playerLaunch(action: CoroutineScope.() -> Unit)
    }
}