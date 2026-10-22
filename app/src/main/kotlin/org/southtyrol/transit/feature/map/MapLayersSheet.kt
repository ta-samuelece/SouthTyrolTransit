@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material.icons.rounded.Signpost
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.R
import org.southtyrol.transit.data.UserSettings
import org.southtyrol.transit.feature.settings.ToggleChip
import org.southtyrol.transit.feature.settings.mapBaseLabel
import org.southtyrol.transit.model.MapBase
import org.southtyrol.transit.model.MobilityKind

/** Opens [MapLayersSheet]; above the locate button, in its size (Settings > Map can hide it). */
@Composable
internal fun MapLayersButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(Icons.Rounded.Layers, contentDescription = stringResource(R.string.map_layers))
    }
}

/**
 * The map's type and what is drawn on it, from the map itself. The overlay chips are the same settings
 * as Settings > Map; the map type is one of four, so it is a row of tiles rather than chips.
 */
@Composable
internal fun MapLayersSheet(
    prefs: UserSettings,
    onMapBase: (MapBase) -> Unit,
    onStops: (Boolean) -> Unit,
    onVehicles: (Boolean) -> Unit,
    onLayer: (MobilityKind, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_map), style = MaterialTheme.typography.titleLarge)

            SheetHeader(stringResource(R.string.map_type))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapBase.entries.forEach { base ->
                    MapTypeTile(base, selected = base == prefs.mapBase, onClick = { onMapBase(base) }, modifier = Modifier.weight(1f))
                }
            }

            SheetHeader(stringResource(R.string.settings_map_layers))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(stringResource(R.string.map_layer_stops), prefs.showStops, onStops)
                ToggleChip(stringResource(R.string.map_layer_vehicles), prefs.showVehicles, onVehicles)
                ToggleChip(stringResource(R.string.map_layer_parking), MobilityKind.PARKING in prefs.mobilityLayers) { onLayer(MobilityKind.PARKING, it) }
                ToggleChip(stringResource(R.string.map_layer_bikes), MobilityKind.BIKE_SHARING in prefs.mobilityLayers) { onLayer(MobilityKind.BIKE_SHARING, it) }
                ToggleChip(stringResource(R.string.map_layer_cars), MobilityKind.CAR_SHARING in prefs.mobilityLayers) { onLayer(MobilityKind.CAR_SHARING, it) }
            }
        }
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun MapTypeTile(base: MapBase, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHighest, shape)
                .border(BorderStroke(3.dp, if (selected) colors.primary else Color.Transparent), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(mapBaseIcon(base), contentDescription = null, tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
        }
        Text(
            stringResource(mapBaseLabel(base)), style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = colors.onSurface, modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

private fun mapBaseIcon(base: MapBase): ImageVector = when (base) {
    MapBase.STANDARD -> Icons.Rounded.Map
    MapBase.DETAILED -> Icons.Rounded.Signpost
    MapBase.AERIAL -> Icons.Rounded.SatelliteAlt
    MapBase.TOPOGRAPHIC -> Icons.Rounded.Terrain
}
