package org.southtyrol.transit.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test
import org.southtyrol.transit.map.CameraFollow.HEADING
import org.southtyrol.transit.map.CameraFollow.NONE
import org.southtyrol.transit.map.CameraFollow.POSITION
import org.southtyrol.transit.map.MapGesture.NORTH
import org.southtyrol.transit.map.MapGesture.PAN
import org.southtyrol.transit.map.MapGesture.ROTATE

class FollowRulesTest {
    @Test fun tapsCycleFreeFollowHeadingFollow() {
        assertEquals(POSITION, FollowRules.tap(NONE))
        assertEquals(HEADING, FollowRules.tap(POSITION))
        assertEquals(POSITION, FollowRules.tap(HEADING))
    }

    @Test fun aDragLetsGoInEveryFollowingState() {
        assertEquals(NONE, FollowRules.gesture(POSITION, PAN))
        assertEquals(NONE, FollowRules.gesture(HEADING, PAN))
    }

    @Test fun aTurnOrTheCompassLeavesHeadingModeButKeepsFollowing() {
        assertEquals(POSITION, FollowRules.gesture(HEADING, ROTATE))
        assertEquals(POSITION, FollowRules.gesture(HEADING, NORTH))
        assertEquals(POSITION, FollowRules.gesture(POSITION, ROTATE))
        assertEquals(POSITION, FollowRules.gesture(POSITION, NORTH))
    }

    @Test fun gesturesOnAFreeMapChangeNothing() {
        assertEquals(NONE, FollowRules.gesture(NONE, PAN))
        assertEquals(NONE, FollowRules.gesture(NONE, ROTATE))
        assertEquals(NONE, FollowRules.gesture(NONE, NORTH))
    }
}
