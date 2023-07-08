package com.toasterofbread.spmp.ui.layout.nowplaying.queue

import LocalPlayerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.toasterofbread.spmp.api.RadioModifier
import com.toasterofbread.spmp.model.mediaitem.MediaItem
import com.toasterofbread.spmp.model.mediaitem.MediaItemPreviewParams
import com.toasterofbread.spmp.model.mediaitem.Song
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.ui.component.multiselect.MediaItemMultiSelectContext
import com.toasterofbread.utils.getContrasted
import com.toasterofbread.utils.modifier.background

@Composable
fun CurrentRadioIndicator(
    accentColourProvider: () -> Color,
    multiselect_context: MediaItemMultiSelectContext
) {
    val player = LocalPlayerState.current
    val horizontal_padding = 15.dp

    Row(Modifier.animateContentSize()) {

        val filters: List<List<RadioModifier>>? = player.player?.radio_filters
        val filters_scroll_state = rememberScrollState()
        var show_radio_info: Boolean by remember { mutableStateOf(false) }
        val radio_item: MediaItem? = player.player?.radio_item.takeIf { item ->
            item !is Song || player.player?.radio_item_index == null
        }

        LaunchedEffect(radio_item) {
            if (radio_item == null) {
                show_radio_info = false
            }
        }

        AnimatedVisibility(radio_item != null && filters != null) {
            IconButton(
                { show_radio_info = !show_radio_info },
                Modifier.padding(start = horizontal_padding)
            ) {
                Box {
                    Icon(Icons.Default.Radio, null)
                    val content_colour = LocalContentColor.current
                    Icon(
                        Icons.Default.Info, null,
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(5.dp, 5.dp)
                            .size(18.dp)
                            // Fill gap in info icon
                            .drawBehind {
                                drawCircle(content_colour, size.width / 4)
                            },
                        tint = accentColourProvider()
                    )
                }
            }
        }

        Crossfade(
            if (show_radio_info) radio_item 
            else if (multiselect_context.is_active) true 
            else filters ?: radio_item
        ) { state ->
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    is MediaItem ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontal_padding)
                                .background(RoundedCornerShape(45), accentColourProvider)
                        ) {
                            state.PreviewLong(
                                MediaItemPreviewParams(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 5.dp, vertical = 3.dp)
                                )
                            )
                        }
                    true ->
                        multiselect_context.InfoDisplay(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = horizontal_padding)
                        )
                    is List<*> -> 
                        FiltersRow(
                            state as List<List<RadioModifier>>,
                            accentColourProvider,
                            Modifier.horizontalScroll(filters_scroll_state)
                        )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FiltersRow(
    filters: List<List<RadioModifier>>,
    accentColourProvider: () -> Color,
    modifier: Modifier = Modifier,
) {
    val player = LocalPlayerState.current
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        Spacer(Modifier)

        val current_filter = player.player?.radio_current_filter
        for (filter in listOf(null) + filters.withIndex()) {
            FilterChip(
                current_filter == filter?.index,
                onClick = {
                    if (player.player?.radio_current_filter != filter?.index) {
                        player.player?.radio_current_filter = filter?.index
                    }
                },
                label = {
                    Text(
                        filter?.value?.joinToString("|") { it.getReadable() }
                            ?: getString("radio_filter_all")
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = LocalContentColor.current,
                    selectedContainerColor = accentColourProvider(),
                    selectedLabelColor = accentColourProvider().getContrasted()
                )
            )
        }
    }
}