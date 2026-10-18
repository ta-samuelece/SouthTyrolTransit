@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.southtyrol.transit.feature.follow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.southtyrol.transit.R
import org.southtyrol.transit.feature.common.groupShape
import org.southtyrol.transit.feature.common.rememberLocationPermission
import org.southtyrol.transit.model.Journey

/**
 * Asks how to follow [journey] (with or without location), then the permissions that needs: location
 * when turned on, notifications on Android 13+. A denied permission never blocks: without location the
 * journey follows the clock, without notifications it is followed in the app. [onStart] returns false
 * when Android refused to start following.
 */
@Composable
fun StartFollowSheet(journey: Journey, onDismiss: () -> Unit, onStart: (useLocation: Boolean) -> Boolean) {
    val context = LocalContext.current
    var useLocation by rememberSaveable { mutableStateOf(true) }
    val start by rememberUpdatedState(onStart)
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun toast(text: Int) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun launch(withLocation: Boolean) {
        if (!start(withLocation)) toast(R.string.follow_start_failed)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast(R.string.follow_notifications_denied)
        launch(useLocation)
    }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else launch(useLocation)
    }
    val locationPermission = rememberLocationPermission { ok ->
        if (!ok) {
            useLocation = false
            toast(R.string.follow_location_denied)
        }
        askNotifications()
    }
    val hasLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.follow_sheet_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.follow_sheet_body, journey.legs.last().to.name),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = groupShape(0, 2),
                    modifier = Modifier.fillMaxWidth().toggleable(useLocation, role = Role.Switch) { useLocation = it },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.follow_use_location), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.follow_use_location_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = useLocation, onCheckedChange = null)
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = groupShape(1, 2),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.follow_without_location), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = { if (useLocation && !hasLocation) locationPermission() else askNotifications() },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Navigation, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.follow_start))
                }
            }
        }
    }
}
