package com.yunget.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yunget.app.ui.theme.accentText
import com.yunget.app.ui.theme.motionTween
import kotlin.math.roundToInt

@Composable
internal fun AppChoiceGroup(
    labels: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
) {
    require(labels.isNotEmpty() && selected in labels.indices)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val useRows = maxWidth / labels.size < (82 * density.fontScale).dp
        if (useRows) {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEachIndexed { index, label ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (selected == index) colors.primaryContainer else colors.surfaceContainerHigh)
                            .selectable(selected == index, role = role, onClick = { onSelected(index) })
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            color = if (selected == index) colors.onPrimaryContainer else colors.onSurface)
                        if (selected == index) Icon(Icons.Outlined.Check, null, tint = colors.onPrimaryContainer)
                    }
                }
            }
        } else {
            BoxWithConstraints(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceContainerHigh).padding(4.dp).selectableGroup(),
            ) {
                val segmentWidth = maxWidth / labels.size
                val segmentWidthPx = with(density) { segmentWidth.toPx() }
                val position by animateFloatAsState(selected.toFloat(), motionTween(210), label = "choicePosition")
                Box {
                    Box(Modifier.matchParentSize()) {
                        Box(
                            Modifier.width(segmentWidth).fillMaxHeight()
                                .offset { IntOffset((segmentWidthPx * position).roundToInt(), 0) }
                                .background(colors.surface, RoundedCornerShape(10.dp)),
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        labels.forEachIndexed { index, label ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                    .selectable(selected == index, role = role, onClick = { onSelected(index) })
                                    .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected == index) colors.accentText else colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
