package com.rnpedometer

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.module.annotations.ReactModule
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit


@ReactModule(name = RNPedometerModule.NAME)
class RNPedometerModule(reactContext: ReactApplicationContext) :
  NativeRNPedometerSpec(reactContext), SensorEventListener, LifecycleEventListener {

  private var sensorManager: SensorManager? = null
  private var stepCounter: Sensor? = null
  private var isListening = false
  private var lastStepCount: Float = 0f
  private var initialStepCount: Float = -1f
  private var listenerCount = 0
  private var sharedPreferences: SharedPreferences

  companion object {
    const val NAME = "RNPedometer"
    private const val PREFS_NAME = "RNPedometerPrefs"
    private const val KEY_INITIAL_STEP_COUNT = "initialStepCount"
    private const val KEY_LAST_STEP_COUNT = "lastStepCount"
    private const val KEY_SAVED_DATE = "savedDate"
    private const val KEY_HISTORY_PREFIX = "history_"
    private const val MAX_HISTORY_DAYS = 30
  }

  init {
    reactContext.addLifecycleEventListener(this)
    sensorManager = reactContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    stepCounter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    sharedPreferences = reactContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Load persisted values
    loadPersistedData()
  }

  private fun loadPersistedData() {
    val savedDate = sharedPreferences.getString(KEY_SAVED_DATE, null)
    val currentDate = getCurrentDate()

    // If it's a new day, save yesterday's data to history and reset counters
    if (savedDate != null && savedDate != currentDate) {
      // Calculate yesterday's total steps before clearing
      val savedInitial = sharedPreferences.getFloat(KEY_INITIAL_STEP_COUNT, -1f)
      val savedLast = sharedPreferences.getFloat(KEY_LAST_STEP_COUNT, 0f)

      if (savedInitial != -1f) {
        val yesterdaySteps = (savedLast - savedInitial).toInt()
        saveStepHistory(savedDate, yesterdaySteps)
      }

      clearPersistedData()
    } else if (savedDate == currentDate) {
      // Load saved values for today
      val savedInitial = sharedPreferences.getFloat(KEY_INITIAL_STEP_COUNT, -1f)
      val savedLast = sharedPreferences.getFloat(KEY_LAST_STEP_COUNT, 0f)

      if (savedInitial != -1f) {
        initialStepCount = savedInitial
        lastStepCount = savedLast
      }
    }

    // Clean up old history (keep only last MAX_HISTORY_DAYS days)
    cleanupOldHistory()
  }

  private fun savePersistedData() {
    sharedPreferences.edit().apply {
      putFloat(KEY_INITIAL_STEP_COUNT, initialStepCount)
      putFloat(KEY_LAST_STEP_COUNT, lastStepCount)
      putString(KEY_SAVED_DATE, getCurrentDate())
      apply()
    }
  }

  private fun clearPersistedData() {
    sharedPreferences.edit().apply {
      remove(KEY_INITIAL_STEP_COUNT)
      remove(KEY_LAST_STEP_COUNT)
      remove(KEY_SAVED_DATE)
      apply()
    }
    initialStepCount = -1f
    lastStepCount = 0f
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

  private fun cleanupOldHistory() {
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.DAY_OF_YEAR, -MAX_HISTORY_DAYS)
    val cutoffDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)

    val allKeys = sharedPreferences.all.keys
    val editor = sharedPreferences.edit()

    for (key in allKeys) {
      if (key.startsWith(KEY_HISTORY_PREFIX)) {
        val date = key.substring(KEY_HISTORY_PREFIX.length)
        if (date < cutoffDate) {
          editor.remove(key)
        }
      }
    }

    editor.apply()
  }

  private fun getStepHistoryInternal(days: Int): WritableArray {
    val historyArray = Arguments.createArray()
    val calendar = Calendar.getInstance()
    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // Get history for the last 'days' days (not including today)
    for (i in 1..days) {
      calendar.time = Date()
      calendar.add(Calendar.DAY_OF_YEAR, -i)
      val date = dateFormat.format(calendar.time)
      val steps = sharedPreferences.getInt("$KEY_HISTORY_PREFIX$date", 0)

      val historyItem = Arguments.createMap().apply {
        putString("date", date)
        putInt("steps", steps)
      }
      historyArray.pushMap(historyItem)
    }

    return historyArray
  }


  override fun addListener(eventName: String) {
    listenerCount += 1
  }

  override fun removeListeners(count: Double) {
    listenerCount = Math.max(0, listenerCount - count.toInt())
    if (listenerCount == 0) {
      val dummyPromise = object : Promise {
        override fun resolve(value: Any?) {}
        override fun reject(code: String, throwable: Throwable?) {}
        override fun reject(code: String, message: String?, throwable: Throwable?) {}
        override fun reject(throwable: Throwable) {}
        override fun reject(throwable: Throwable, userInfo: WritableMap) {}
        override fun reject(code: String, userInfo: WritableMap) {}
        override fun reject(code: String, throwable: Throwable?, userInfo: WritableMap) {}
        override fun reject(code: String, message: String?, userInfo: WritableMap) {}
        override fun reject(
          code: String?,
          message: String?,
          throwable: Throwable?,
          userInfo: WritableMap?
        ) {}
        override fun reject(message: String) {}
        override fun reject(code: String, message: String?) {}
      }
      stopStepCounterUpdate(dummyPromise)
    }
  }

  override fun startStepCounterUpdate(promise: Promise) {
    if (stepCounter == null) {
      promise.reject("E_STEP_COUNTER", "Step counter sensor not available on this device")
      return
    }

    if (!isListening) {
      sensorManager?.registerListener(
        this,
        stepCounter,
        SensorManager.SENSOR_DELAY_NORMAL
      )
      isListening = true
      promise.resolve(true)
    } else {
      promise.resolve(false)
    }
  }

  override fun stopStepCounterUpdate(promise: Promise) {
    if (isListening) {
      sensorManager?.unregisterListener(this)
      isListening = false
      promise.resolve(true)
    } else {
      promise.resolve(false)
    }
  }

  override fun isStepCountingAvailable(promise: Promise) {
    promise.resolve(stepCounter != null)
  }

  override fun getStepHistory(days: Double, promise: Promise) {
    try {
      val requestedDays = days.toInt().coerceIn(1, MAX_HISTORY_DAYS)
      val history = getStepHistoryInternal(requestedDays)
      promise.resolve(history)
    } catch (e: Exception) {
      promise.reject("E_HISTORY_ERROR", "Failed to retrieve step history: ${e.message}", e)
    }
  }

  override fun enableBackgroundSync(promise: Promise) {
    try {
      // Schedule daily work to save step history
      val workRequest = PeriodicWorkRequestBuilder<StepCounterWorker>(
        1, TimeUnit.DAYS
      ).build()

      WorkManager.getInstance(reactApplicationContext)
        .enqueueUniquePeriodicWork(
          "StepCounterDailySync",
          ExistingPeriodicWorkPolicy.KEEP,
          workRequest
        )

      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("E_BACKGROUND_SYNC", "Failed to enable background sync: ${e.message}", e)
    }
  }

  override fun disableBackgroundSync(promise: Promise) {
    try {
      WorkManager.getInstance(reactApplicationContext)
        .cancelUniqueWork("StepCounterDailySync")

      promise.resolve(true)
    } catch (e: Exception) {
      promise.reject("E_BACKGROUND_SYNC", "Failed to disable background sync: ${e.message}", e)
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
      val steps = event.values[0]

      if (initialStepCount < 0) {
        initialStepCount = steps
        lastStepCount = steps
        savePersistedData()
      }

      val stepsDelta = steps - lastStepCount
      lastStepCount = steps

      val totalSteps = steps - initialStepCount

      // Save updated values
      savePersistedData()

      sendStepUpdate(stepsDelta.toInt(), totalSteps.toInt())
    }
  }

  private fun sendStepUpdate(stepsDelta: Int, totalSteps: Int) {
    // Only send updates if we have listeners
    if (listenerCount > 0) {
      val params = Arguments.createMap().apply {
        putInt("steps", stepsDelta)
        putInt("totalSteps", totalSteps)
        putDouble("timestamp", System.currentTimeMillis().toDouble())
      }

      reactApplicationContext
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("StepCounterUpdate", params)
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    // Handle accuracy changes if needed
  }

  override fun onHostResume() {
    if (isListening) {
      sensorManager?.registerListener(
        this,
        stepCounter,
        SensorManager.SENSOR_DELAY_NORMAL
      )
    }
  }

  override fun onHostPause() {
    if (isListening) {
      sensorManager?.unregisterListener(this)
    }
  }

  override fun onHostDestroy() {
    sensorManager?.unregisterListener(this)
    listenerCount = 0
  }
}
