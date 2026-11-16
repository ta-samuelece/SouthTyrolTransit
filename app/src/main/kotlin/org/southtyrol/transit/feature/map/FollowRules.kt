package org.southtyrol.transit.feature.map

import org.southtyrol.transit.map.CameraFollow
import org.southtyrol.transit.map.MapGesture

/**
 * How the Map tab's locate button moves between following states, as in Google Maps: a tap goes from free
 * to following, then to following with heading, and back to following; dragging the map lets go.
 */
object FollowRules {
    /** A tap on the locate button. From [CameraFollow.NONE] it only applies once a location fix is found. */
    fun tap(current: CameraFollow): CameraFollow = when (current) {
        CameraFollow.NONE -> CameraFollow.POSITION
        CameraFollow.POSITION -> CameraFollow.HEADING
        CameraFollow.HEADING -> CameraFollow.POSITION
    }

    /** A camera move the user made: a drag lets go, a turn or the compass leaves heading mode. Zoom keeps following. */
    fun gesture(current: CameraFollow, gesture: MapGesture): CameraFollow = when {
        current == CameraFollow.NONE -> CameraFollow.NONE
        gesture == MapGesture.PAN -> CameraFollow.NONE
        else -> CameraFollow.POSITION
    }
}
