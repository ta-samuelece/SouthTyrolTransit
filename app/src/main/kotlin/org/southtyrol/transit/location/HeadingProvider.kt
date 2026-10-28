package org.southtyrol.transit.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.southtyrol.transit.model.Point

/** The compass: which way the top of the screen points, for the cone around the user's position on the map. */
interface HeadingProvider {
    /** Degrees clockwise from magnetic north, in [0, 360), while collected; empty on a device without a compass. */
    fun headings(): Flow<Float>

    /** What to add to a magnetic heading at [point] for one from true north (the map's north). */
    fun declination(point: Point): Float
}

class AndroidHeadingProvider(context: Context) : HeadingProvider {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)

    override fun headings(): Flow<Float> = callbackFlow {
        val manager = sensors
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (manager == null || sensor == null) { close(); return@callbackFlow }
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Relative to the screen's top edge, so the cone stays right in landscape too.
                val (x, y) = when (displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
                SensorManager.getOrientation(remapped, orientation)
                trySend(((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat())
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }

    override fun declination(point: Point): Float =
        GeomagneticField(point.latitude.toFloat(), point.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
}
