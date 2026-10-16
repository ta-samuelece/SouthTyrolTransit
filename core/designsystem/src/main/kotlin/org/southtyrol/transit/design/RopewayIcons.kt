package org.southtyrol.transit.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * Material Symbols Rounded, filled (Apache License 2.0), for the ropeway modes that the Compose icon set
 * lacks. Paths are the symbols' own, drawn on their 960 grid whose y runs from -960 to 0.
 */
internal object RopewayIcons {
    val GondolaLift: ImageVector by lazy {
        symbol(
            "GondolaLift",
            "M200-120q-33 0-56.5-23.5T120-200v-240q0-66 47-113t113-47h160v-109L89-613q-18 5-33.5-6.5T40-651q0-13 7.5-23T68-688l177-48q-2-5-3.5-11t-1.5-13q0-25 17.5-42.5T300-820q23 0 40 15t19 38l81-22v-11q0-17 11.5-28.5T480-840q14 0 24.5 8t14.5 21l87-23q-3-6-4.5-12.5T600-860q0-25 17.5-42.5T660-920q23 0 40.5 16t19.5 39l151-42q18-5 33.5 6.5T920-869q0 13-7.5 23T892-832L520-731v131h160q66 0 113 47t47 113v240q0 33-23.5 56.5T760-120H200Zm0-240h133v-160h-53q-33 0-56.5 23.5T200-440v80Zm213 0h133v-160H413v160Zm214 0h133v-80q0-33-23.5-56.5T680-520h-53v160Z",
        )
    }

    val Funicular: ImageVector by lazy {
        symbol(
            "Funicular",
            "M89-53q-18 5-33.5-6.5T40-91q0-13 7.5-23T68-128l172-47v-105h-80q-17 0-28.5-11.5T120-320v-400q-17 0-28.5-11.5T80-760q0-17 11.5-28.5T120-800h80v-40q0-17 11.5-28.5T240-880h480q17 0 28.5 11.5T760-840v40h80q17 0 28.5 11.5T880-760q0 17-11.5 28.5T840-720v320q0 17-11.5 28.5T800-360h-80v55l151-42q18-5 33.5 6.5T920-309q0 13-7.5 23T892-272L89-53Zm271-154 240-66v-87h-80v40q0 17-11.5 28.5T480-280H360v73ZM200-480h240v-240H200v240Zm320-80h240v-160H520v160Z",
        )
    }

    private fun symbol(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f)
            .addGroup(translationY = 960f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .clearGroup()
            .build()
}
