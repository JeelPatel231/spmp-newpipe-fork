package com.toasterofbread.spmp.ui.layout.apppage.settingspage

import LocalPlayerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.toasterofbread.composekit.platform.PlatformPreferences
import com.toasterofbread.composekit.settings.ui.item.BasicSettingsValueState
import com.toasterofbread.composekit.settings.ui.item.LargeToggleSettingsItem
import com.toasterofbread.composekit.settings.ui.item.SettingsValueState
import com.toasterofbread.composekit.utils.composable.ShapedIconButton
import com.toasterofbread.spmp.model.settings.Settings
import com.toasterofbread.spmp.model.settings.category.DiscordAuthSettings
import com.toasterofbread.spmp.model.settings.category.InternalSettings
import com.toasterofbread.spmp.platform.AppContext
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.ui.layout.DiscordAccountPreview
import com.toasterofbread.spmp.ui.layout.DiscordLoginConfirmation

fun getDiscordAuthItem(
    context: AppContext,
    info_only: Boolean = false,
    ignore_prerequisite: Boolean = false,
    StartIcon: (@Composable () -> Unit)? = null
): LargeToggleSettingsItem {
    val discord_auth: SettingsValueState<String> = SettingsValueState<String>(DiscordAuthSettings.Key.DISCORD_ACCOUNT_TOKEN.getName())
        .init(context.getPrefs(), Settings::provideDefault)

    val prerequisite: SettingsValueState<Boolean> = SettingsValueState<Boolean>(InternalSettings.Key.DISCORD_WARNING_ACCEPTED.getName())
        .init(context.getPrefs(), Settings::provideDefault)

    return LargeToggleSettingsItem(
        object : BasicSettingsValueState<Boolean> {
            override fun getKeys(): List<String> = discord_auth.getKeys()
            override fun get(): Boolean = discord_auth.get().isNotEmpty()
            override fun set(value: Boolean) {
                if (!value) {
                    discord_auth.set("")
                }
            }

            override fun init(prefs: PlatformPreferences, defaultProvider: (String) -> Any): BasicSettingsValueState<Boolean> = this
            override fun release(prefs: PlatformPreferences) {}
            override fun setEnableAutosave(value: Boolean) {}
            override fun reset() = discord_auth.reset()
            override fun PlatformPreferences.Editor.save() = with (discord_auth) { save() }
            override fun getDefault(defaultProvider: (String) -> Any): Boolean =
                (defaultProvider(DiscordAuthSettings.Key.DISCORD_ACCOUNT_TOKEN.getName()) as String).isNotEmpty()

            @Composable
            override fun onChanged(key: Any?, action: (Boolean) -> Unit) {}
        },
        show_button = !info_only,
        enabledContent = { modifier ->
            Row(
                modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StartIcon?.invoke()

                var account_token: String by mutableStateOf(discord_auth.get())

                val auth = discord_auth.get()
                if (auth.isNotEmpty()) {
                    account_token = auth
                }
                if (account_token.isNotEmpty()) {
                    DiscordAccountPreview(account_token)
                }
            }
        },
        disabledContent = {
            StartIcon?.invoke()
        },
        disabled_text = getString("auth_not_signed_in"),
        enable_button = getString("auth_sign_in"),
        disable_button = getString("auth_sign_out"),
        warningDialog = { dismiss, openPage ->
            DiscordLoginConfirmation { manual ->
                dismiss()
                if (manual != null) {
                    openPage(PrefsPageScreen.DISCORD_LOGIN.ordinal, manual)
                }
            }
        },
        infoButton = { enabled, _ ->
            if (info_only) {
                return@LargeToggleSettingsItem
            }

            val player = LocalPlayerState.current
            var show_info_dialog: Boolean by remember { mutableStateOf(false) }

            if (show_info_dialog) {
                DiscordLoginConfirmation(true) {
                    show_info_dialog = false
                }
            }

            ShapedIconButton(
                { show_info_dialog = !show_info_dialog },
                shape = CircleShape,
                colours = IconButtonDefaults.iconButtonColors(
                    containerColor = if (enabled) player.theme.background else player.theme.vibrant_accent,
                    contentColor = if (enabled) player.theme.on_background else player.theme.on_accent
                )
            ) {
                Icon(
                    if (enabled) Icons.Default.Settings
                    else Icons.Default.Info,
                    null
                )
            }
        },
        prerequisite_value = if (ignore_prerequisite) null else prerequisite
    ) { target, setEnabled, _, openPage ->
        if (target) {
            openPage(PrefsPageScreen.DISCORD_LOGIN.ordinal, null)
        }
        else {
            setEnabled(false)
        }
    }
}
