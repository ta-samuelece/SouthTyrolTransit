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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.southtyrol.transit.R
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.follow.ActiveJourney
import org.southtyrol.transit.follow.FollowController
import org.southtyrol.transit.follow.FollowText
import org.southtyrol.transit.follow.RideSession
import org.southtyrol.transit.follow.RideView
import org.southtyrol.transit.model.RideStage
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TripDetail
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class FollowRideViewModel @Inject constructor(active: ActiveJourney, private val controller: FollowController) : ViewModel() {
    val session = active.session
    val ride = active.ride
    val rideView = active.rideView

    fun start(ride: RideSession): Boolean = controller.start(ride)

    fun stop() = controller.stop()
}

/** A run the user may follow: one that has not ended and is not cancelled. */
fun TripDetail.canFollow(now: Instant): Boolean {
    val last = stops.lastOrNull() ?: return false
    return state != ServiceState.CANCELLED && (last.predictedArrival ?: last.scheduledArrival).isAfter(now)
}

/** The [RideSession] that follows this run; [liveRef] is the journey planner's reference it was opened with, if any. */
fun TripDetail.rideSession(liveRef: String = ""): RideSession =
    RideSession(trip.id, serviceDate, liveRef, route.shortName.ifBlank { route.longName }, route.mode, trip.headsign)

/** Following a ride from a screen: the ride followed now, [request] to start one, [stop]. */
class RideFollower(val ride: RideSession?, val request: (RideSession) -> Unit, val stop: () -> Unit)

/**
 * Starts following a ride: when a journey or another ride is followed, first asks whether to follow this
 * one instead (one at a time); then asks for notifications on Android 13+. A denied permission never
 * blocks: the ride is followed in the app. [onStarted] runs once following began.
 */
@Composable
fun rememberRideFollower(onStarted: (RideSession) -> Unit = {}, viewModel: FollowRideViewModel = hiltViewModel()): RideFollower {
    val context = LocalContext.current
    val res = LocalResources.current
    val session by viewModel.session.collectAsStateWithLifecycle()
    val ride by viewModel.ride.collectAsStateWithLifecycle()
    val started by rememberUpdatedState(onStarted)
    var pending by remember { mutableStateOf<RideSession?>(null) }
    var asking by remember { mutableStateOf<RideSession?>(null) }

    fun begin(new: RideSession) {
        if (viewModel.start(new)) started(new)
        else Toast.makeText(context, R.string.follow_start_failed, Toast.LENGTH_LONG).show()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(context, R.string.follow_notifications_denied, Toast.LENGTH_LONG).show()
        asking?.let(::begin)
        asking = null
    }
    fun proceed(new: RideSession) {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) begin(new)
        else {
            asking = new
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    pending?.let { new ->
        val line = FollowText.line(res, new)
        val current = ride
        val journey = session
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
            title = { Text(stringResource(R.string.ride_replace_title, line)) },
            text = {
                Text(
                    when {
                        journey != null -> stringResource(R.string.ride_replace_journey, journey.journey.legs.last().to.name)
                        current != null -> stringResource(R.string.ride_replace_ride, FollowText.line(res, current))
                        else -> ""
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { pending = null; proceed(new) }) { Text(stringResource(R.string.ride_replace_confirm, line)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.ride_replace_keep)) }
            },
        )
    }

    return RideFollower(
        ride = ride,
        request = { new ->
            val current = ride
            when {
                current != null && current.tripId == new.tripId && current.serviceDate == new.serviceDate -> Unit
                current != null || session != null -> pending = new
                else -> proceed(new)
            }
        },
        stop = viewModel::stop,
    )
}

/**
 * The trip screen's card of the ride being followed: where the vehicle is (the next stop, the stop it
 * stands at, or the departure), the countdown to it, then the last stop and when it is reached. Words
 * from [FollowText], as the notification and the strip say them.
 */
@Composable
fun FollowingRideCard(ride: RideSession, view: RideView, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    val now = rememberNow(1_000)
    val status = LocalStatusColors.current
    val step = view.step
    val stops = view.detail.stops
    val label = stringResource(
        when (step.stage) {
            RideStage.WAITING -> R.string.ride_card_leaves
            RideStage.MOVING -> R.string.ride_card_next
            RideStage.AT_STOP -> R.string.ride_card_at
            RideStage.ARRIVED -> R.string.ride_card_arrived
            RideStage.CANCELLED -> org.southtyrol.transit.design.R.string.ds_cancelled
        },
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // One TalkBack sentence, as the notification says it.
            Column(
                Modifier.padding(20.dp).clearAndSetSemantics {
                    heading()
                    contentDescription = label + ". " + FollowText.rideText(res, ride, view, now)
                },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
                if (step.stage == RideStage.CANCELLED) {
                    Text(FollowText.rideText(res, ride, view, now), style = MaterialTheme.typography.titleMedium, color = status.cancelled)
                    return@Column
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stops[step.stopIndex].stop.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (step.stage != RideStage.ARRIVED) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val color = if (view.live) status.live else Color.Unspecified
                        if (view.live) Icon(Icons.Rounded.Podcasts, contentDescription = null, tint = status.live, modifier = Modifier.size(20.dp))
                        Text(Format.countdown(step.at, now), style = TimeStyles.hero, color = color, maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val delay = (step.delaySeconds ?: 0) / 60
                    if (delay > 0) Text(stringResource(org.southtyrol.transit.design.R.string.ds_delay_late, delay), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = status.delayed)
                    Text(Format.time(step.at) + " · " + FollowText.rideSource(res, view), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (step.stage != RideStage.ARRIVED && step.stage != RideStage.CANCELLED) Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val last = stops.last()
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.ride_last_stop, last.stop.name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(Format.time(last.predictedArrival ?: last.scheduledArrival), style = TimeStyles.medium)
            }
        }
    }
}
