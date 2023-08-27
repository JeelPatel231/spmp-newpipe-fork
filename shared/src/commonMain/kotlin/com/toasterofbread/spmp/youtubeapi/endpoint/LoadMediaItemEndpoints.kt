package com.toasterofbread.spmp.youtubeapi.endpoint

import com.toasterofbread.spmp.model.mediaitem.ArtistData
import com.toasterofbread.spmp.model.mediaitem.PlaylistData
import com.toasterofbread.spmp.model.mediaitem.SongData
import com.toasterofbread.spmp.ui.component.mediaitemlayout.MediaItemLayout
import com.toasterofbread.spmp.youtubeapi.YoutubeApi

abstract class LoadSongEndpoint: YoutubeApi.Endpoint() {
    abstract suspend fun loadSong(song_data: SongData): Result<Unit>
}

abstract class LoadArtistEndpoint: YoutubeApi.Endpoint() {
    abstract suspend fun loadArtist(artist_data: ArtistData): Result<Unit>
}

abstract class LoadPlaylistEndpoint: YoutubeApi.Endpoint() {
    abstract suspend fun loadPlaylist(playlist_data: PlaylistData, continuation: MediaItemLayout.Continuation? = null): Result<Unit>
}