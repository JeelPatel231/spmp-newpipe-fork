package com.spectre7.spmp.model.mediaitem

import SpMp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spectre7.spmp.api.*
import com.spectre7.spmp.model.mediaitem.data.ArtistItemData
import com.spectre7.spmp.resources.getString
import com.spectre7.spmp.ui.component.ArtistPreviewLong
import com.spectre7.spmp.ui.component.ArtistPreviewSquare
import com.spectre7.spmp.ui.component.MediaItemLayout
import kotlin.concurrent.thread

class Artist private constructor (
    id: String,
    var is_for_item: Boolean = false
): MediaItem(id), MediaItemWithLayouts {

    override val url: String get() = "https://music.youtube.com/channel/$id"
    override val data: ArtistItemData = ArtistItemData(this)

    val subscribe_channel_id: String? get() = data.subscribe_channel_id
    val subscriber_count: Int? get() = data.subscriber_count

    var subscribed: Boolean? by mutableStateOf(null)
    var is_own_channel: Boolean by mutableStateOf(false)

    override val feed_layouts: List<MediaItemLayout>?
        @Composable
        get() = data.feed_layouts

    override suspend fun getFeedLayouts(): Result<List<MediaItemLayout>> {
        data.feed_layouts?.also { return Result.success(it) }
        val result = loadGeneralData()
        if (result.isFailure) {
            return result.cast()
        }
        return data.feed_layouts?.let {
            Result.success(it)
        } ?: Result.failure(RuntimeException("Feed layouts not loaded"))
    }

    fun getReadableSubscriberCount(): String {
        return subscriber_count?.let { subs ->
            getString("artist_x_subscribers").replace("\$x", amountToString(subs, SpMp.ui_language))
        } ?: ""
    }

    fun updateSubscribed() {
        check(!is_for_item)

        if (is_own_channel) {
            return
        }
        subscribed = isSubscribedToArtist(this).getOrNull()
    }

    fun toggleSubscribe(toggle_before_fetch: Boolean = false, onFinished: ((success: Boolean, subscribing: Boolean) -> Unit)? = null) {
        check(!is_for_item)
        check(Api.ytm_authenticated)

        thread {
            if (subscribed == null) {
                throw IllegalStateException()
            }

            val target = !subscribed!!

            if (toggle_before_fetch) {
                subscribed = target
            }

            subscribeOrUnsubscribeArtist(this, target).getOrThrowHere()
            updateSubscribed()

            onFinished?.invoke(subscribed == target, target)
        }
    }

    fun editArtistData(action: ArtistItemData.() -> Unit): Artist {
        if (is_for_item || is_temp) {
            action(data)
        }
        else {
            editData {
                action(this as ArtistItemData)
            }
        }
        return this
    }

    @Composable
    override fun PreviewSquare(params: MediaItemPreviewParams) {
        ArtistPreviewSquare(this, params)
    }

    @Composable
    override fun PreviewLong(params: MediaItemPreviewParams) {
        ArtistPreviewLong(this, params)
    }

    private val is_temp: Boolean get() = id.isBlank()

    companion object {
        private val artists: MutableMap<String, Artist> = mutableMapOf()

        fun fromId(id: String): Artist {
            check(id.isNotBlank())

            synchronized(artists) {
                return artists.getOrPut(id) {
                    val artist = Artist(id)
                    artist.loadFromCache()
                    return@getOrPut artist
                }
            }
        }

        fun createForItem(item: MediaItem): Artist {
            synchronized(artists) {
                val id = "FS" + item.id
                return artists.getOrPut(id) {
                    val artist = Artist(id, true)
                    artist.loadFromCache()
                    return@getOrPut artist
                }
            }
        }

        fun createTemp(id: String = ""): Artist {
            return Artist(id)
        }
    }
}