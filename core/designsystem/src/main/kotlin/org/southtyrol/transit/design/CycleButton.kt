@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One choice among a few values as a single button: its [label] small on top beside a cycle icon, the current
 * value under it, and each tap moves to the next of [options], wrapping round. Saves the room of a connected
 * group where the values are few and the current one is what matters (the Plan lip's Prefer, Walking and
 * Longest walk).
 *
 * Motion, on the theme's motion scheme so reduced motion calms it: the button shrinks a little while pressed and
 * springs back, the icon turns half a turn per tap, and the new value slides up in as the old one leaves.
 *
 * TalkBack reads [description] (the full name) and the current value, and offers "Switch to [next]" as the action.
 */
@Composable
fun <T> CycleButton(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    valueLabel: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    description: String = label,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
) {
    val motion = MaterialTheme.motionScheme
    val index = options.indexOf(selected).coerceAtLeast(0)
    val next = options[(index + 1) % options.size]
    val value = valueLabel(selected)
    val nextAction = stringResource(R.string.ds_cycle_next, valueLabel(next))
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, motion.fastSpatialSpec(), label = "cyclePress")
    var turns by rememberSaveable { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(turns * 180f, motion.defaultSpatialSpec(), label = "cycleIcon")
    val advance = { turns++; onSelect(next) }
    Column(
        modifier
            .heightIn(min = 56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clearAndSetSemantics {
                contentDescription = "$description: $value"
                role = Role.Button
                onClick(label = nextAction) { advance(); true }
            }
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = advance)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { -it } + fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = true))
            },
            label = "cycleValue",
        ) { shown ->
            Text(shown, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
