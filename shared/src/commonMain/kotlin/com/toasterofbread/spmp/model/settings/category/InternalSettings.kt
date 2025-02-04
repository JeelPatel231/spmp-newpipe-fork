package com.toasterofbread.spmp.model.settings.category

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.ui.graphics.vector.ImageVector
import com.toasterofbread.spmp.model.settings.SettingsKey
import com.toasterofbread.spmp.platform.AppContext

data object InternalSettings: SettingsCategory("internal") {
    override val keys: List<SettingsKey> = Key.values().toList()

    override fun getPage(): Page? = null

    enum class Key: SettingsKey {
        TOPBAR_MODE_HOME,
        TOPBAR_MODE_NOWPLAYING,
        DISCORD_WARNING_ACCEPTED;

        override val category: SettingsCategory get() = InternalSettings

        @Suppress("UNCHECKED_CAST")
        override fun <T> getDefaultValue(): T =
            when (this) {
                TOPBAR_MODE_HOME -> MusicTopBarMode.LYRICS.ordinal
                TOPBAR_MODE_NOWPLAYING -> MusicTopBarMode.LYRICS.ordinal
                DISCORD_WARNING_ACCEPTED -> false
            } as T
    }
}

enum class MusicTopBarMode {
    VISUALISER, LYRICS;

    fun getIcon(): ImageVector = when (this) {
        LYRICS -> Icons.Default.Lyrics
        VISUALISER -> Icons.Default.GraphicEq
    }

    fun getNext(can_show_visualiser: Boolean): MusicTopBarMode {
        val next =
            if (ordinal == 0) values().last()
            else values()[ordinal - 1]

        if (!can_show_visualiser && next == VISUALISER) {
            return next.getNext(false)
        }

        return next
    }

    companion object {
        val default: MusicTopBarMode get() = LYRICS
    }
}
