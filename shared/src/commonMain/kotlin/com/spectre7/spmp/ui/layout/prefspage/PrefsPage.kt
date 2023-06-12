@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.spectre7.spmp.ui.layout.prefspage

import SpMp
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spectre7.composesettings.ui.SettingsInterface
import com.spectre7.settings.model.*
import com.spectre7.spmp.model.*
import com.spectre7.spmp.platform.composable.BackHandler
import com.spectre7.spmp.resources.getString
import com.spectre7.spmp.ui.component.PillMenu
import com.spectre7.spmp.ui.layout.*
import com.spectre7.spmp.ui.theme.Theme
import com.spectre7.utils.modifier.background
import org.jetbrains.compose.resources.*

internal enum class PrefsPageScreen {
    ROOT,
    YOUTUBE_MUSIC_LOGIN,
    YOUTUBE_MUSIC_MANUAL_LOGIN,
    DISCORD_LOGIN,
    DISCORD_MANUAL_LOGIN
}
internal enum class PrefsPageCategory {
    GENERAL,
    FEED,
    LIBRARY,
    THEME,
    LYRICS,
    DOWNLOAD,
    DISCORD_STATUS,
    OTHER;

    @OptIn(ExperimentalResourceApi::class)
    @Composable
    fun getIcon(filled: Boolean = false): ImageVector = when (this) {
        GENERAL -> if (filled) Icons.Filled.Settings else Icons.Outlined.Settings
        FEED -> if (filled) Icons.Filled.FormatListBulleted else Icons.Outlined.FormatListBulleted
        LIBRARY -> if (filled) Icons.Filled.LibraryMusic else Icons.Outlined.LibraryMusic
        THEME -> if (filled) Icons.Filled.Palette else Icons.Outlined.Palette
        LYRICS -> if (filled) Icons.Filled.MusicNote else Icons.Outlined.MusicNote
        DOWNLOAD -> if (filled) Icons.Filled.Download else Icons.Outlined.Download
        DISCORD_STATUS -> resource("drawable/ic_discord.xml").readBytesSync().toImageVector(LocalDensity.current)
        OTHER -> if (filled) Icons.Filled.MoreHoriz else Icons.Outlined.MoreHoriz
    }

    fun getTitle(): String = when (this) {
        GENERAL -> getString("s_cat_general")
        FEED -> getString("s_cat_home_page")
        LIBRARY -> getString("s_cat_library")
        THEME -> getString("s_cat_theming")
        LYRICS -> getString("s_cat_lyrics")
        DOWNLOAD -> getString("s_cat_download")
        DISCORD_STATUS -> getString("s_cat_discord_status")
        OTHER -> getString("s_cat_other")
    }

    fun getDescription(): String = when (this) {
        GENERAL -> getString("s_cat_desc_general")
        FEED -> getString("s_cat_desc_home_page")
        LIBRARY -> getString("s_cat_desc_library")
        THEME -> getString("s_cat_desc_theming")
        LYRICS -> getString("s_cat_desc_lyrics")
        DOWNLOAD -> getString("s_cat_desc_download")
        DISCORD_STATUS -> getString("s_cat_desc_discord_status")
        OTHER -> getString("s_cat_desc_other")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun PrefsPage(pill_menu: PillMenu, bottom_padding: Dp, modifier: Modifier = Modifier, close: () -> Unit) {
    val ytm_auth = remember {
        SettingsValueState(
            Settings.KEY_YTM_AUTH.name,
            converter = { set ->
                set?.let { YoutubeMusicAuthInfo(it as Set<String>) } ?: YoutubeMusicAuthInfo()
            }
        ).init(Settings.prefs, Settings.Companion::provideDefault)
    }

    var current_category: PrefsPageCategory? by remember { mutableStateOf(null) }
    val category_open by remember { derivedStateOf { current_category != null } }
    val settings_interface: SettingsInterface =
        rememberPrefsPageSettingsInterfade(pill_menu, ytm_auth, { current_category }, { current_category = null })
    val show_reset_confirmation = remember { mutableStateOf(false) }

    ResetConfirmationDialog(
        show_reset_confirmation,
        { 
            if (category_open) {
                settings_interface.current_page.resetKeys()
            }
            else {
                TODO("Reset keys in all categories (w/ different confirmation text)")
            }
        }
    )

    BackHandler(category_open) {
        current_category = null
    }

    val extra_action: @Composable PillMenu.Action.(action_count: Int) -> Unit = remember {{
        if (it == 1) {
            ActionButton(
                Icons.Filled.Refresh
            ) {
                show_reset_confirmation.value = true
            }
        }
    }}

    DisposableEffect(settings_interface.current_page) {
        if (settings_interface.current_page.id == PrefsPageScreen.ROOT.ordinal) {
            pill_menu.addExtraAction(action = extra_action)
        }
        else {
            pill_menu.removeExtraAction(extra_action)
        }

        onDispose {
            pill_menu.removeExtraAction(extra_action)
        }
    }

    Column(modifier) {
        MusicTopBar(
            Settings.INTERNAL_TOPBAR_MODE_SETTINGS,
            Modifier.fillMaxWidth()
        )

        Crossfade(category_open || settings_interface.current_page.id!! != PrefsPageScreen.ROOT.ordinal) { open ->
            if (!open) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        bottom = bottom_padding,
                        top = 20.dp,
                        start = 20.dp,
                        end = 20.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "設定",
                                style = MaterialTheme.typography.displaySmall
                            )

                            if (SpMp.context.canOpenUrl()) {
                                IconButton({ SpMp.context.openUrl(getString("project_url")) }) {
                                    Icon(painterResource("drawable/ic_github.xml"), null)
                                }
                            }
                        }
                    }

                    item {
                        val own_channel = remember { mutableStateOf(ytm_auth.value.getOwnChannelOrNull()) }
                        val item = remember { 
                            getYtmAuthItem(ytm_auth, own_channel).apply {
                                initialise(SpMp.context, Settings.prefs, Settings.Companion::provideDefault) 
                            } 
                        }
                        item.GetItem(
                            Theme.current,
                            settings_interface::openPageById,
                            settings_interface::openPage
                        )
                    }

                    items(PrefsPageCategory.values()) { category ->
                        ElevatedCard(
                            onClick = { current_category = category },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(15.dp)
                            ) {
                                Icon(category.getIcon(), null)
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(category.getTitle(), style = MaterialTheme.typography.titleMedium)
                                    Text(category.getDescription(), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
            else {
                BoxWithConstraints(
                    Modifier
                        .background(Theme.current.background_provider)
                        .pointerInput(Unit) {}
                ) {
                    settings_interface.Interface(
                        SpMp.context.getScreenHeight() - SpMp.context.getStatusBarHeight(),
                        content_padding = PaddingValues(bottom = bottom_padding)
                    )
                }
            }
        }
    }
}
