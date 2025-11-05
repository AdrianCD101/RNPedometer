package com.rnpedometer

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StepCounterWorker(
  context: Context,
  workerParams: WorkerParameters
) : Worker(context, workerParams), SensorEventListener {

  private var sensorManager: SensorManager? = null
  private var stepCounter: Sensor? = null
  private var sharedPreferences: SharedPreferences
  private val latch = CountDownLatch(1)
  private var currentStepCount: Float = 0f

  companion object {
    private const val PREFS_NAME = "RNPedometerPrefs"
    private const val KEY_INITIAL_STEP_COUNT = "initialStepCount"
    private const val KEY_LAST_STEP_COUNT = "lastStepCount"
    private const val KEY_SAVED_DATE = "savedDate"
    private const val KEY_HISTORY_PREFIX = "history_"
  }

  init {
    sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  override fun doWork(): Result {
    try {
      val savedDate = sharedPreferences.getString(KEY_SAVED_DATE, null)
      val currentDate = getCurrentDate()

      // Only save history if it's a new day
      if (savedDate != null && savedDate != currentDate) {
        // Register sensor to get current reading
        stepCounter?.let { sensor ->
          sensorManager?.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL
          )

          // Wait for sensor reading (max 5 seconds)
          latch.await(5, TimeUnit.SECONDS)

          sensorManager?.unregisterListener(this)

          // Calculate and save yesterday's steps
          val savedInitial = sharedPreferences.getFloat(KEY_INITIAL_STEP_COUNT, -1f)
          val savedLast = sharedPreferences.getFloat(KEY_LAST_STEP_COUNT, 0f)

          if (savedInitial != -1f && currentStepCount > 0) {
            // Use the current sensor reading as the last count for yesterday
            val yesterdaySteps = (currentStepCount - savedInitial).toInt()
            saveStepHistory(savedDate, yesterdaySteps)

            // Update baseline for today
            sharedPreferences.edit().apply {
              putFloat(KEY_INITIAL_STEP_COUNT, currentStepCount)
              putFloat(KEY_LAST_STEP_COUNT, currentStepCount)
              putString(KEY_SAVED_DATE, currentDate)
              apply()
            }
          }
        }

        return Result.success()
      }

      return Result.success()
    } catch (e: Exception) {
      return Result.retry()
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
      currentStepCount = event.values[0]
      latch.countDown()
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    // Not needed
  }

  private fun getCurrentDate(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return dateFormat.format(Date())
  }

  private fun saveStepHistory(date: String, steps: Int) {
    sharedPreferences.edit().apply {
      putInt("$KEY_HISTORY_PREFIX$date", steps)
      apply()
    }
  }
}
