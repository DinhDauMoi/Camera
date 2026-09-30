package com.dinh.aicamera.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import com.dinh.aicamera.receiver.GoldenHourReceiver
import com.dinh.aicamera.ui.AppPreferences
import java.util.Calendar

object GoldenHourHelper {
    private const val TAG = "GoldenHourHelper"
    private const val REQUEST_CODE = 9901

    fun scheduleNextGoldenHour(context: Context) {
        val prefs = AppPreferences(context)
        if (!prefs.isGoldenHourEnabled) return

        val location = getLastKnownLocation(context)
        val lat = location?.latitude ?: 16.0
        val lon = location?.longitude ?: 108.0

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Tính giờ hoàng hôn hôm nay trừ đi 20 phút
        var targetTime = SolarTimeHelper.calculateSunsetMillis(lat, lon, cal) - (20 * 60 * 1000L)
        if (targetTime <= now) {
            // Đã qua thời điểm hôm nay -> Lên lịch cho hoàng hôn ngày mai
            cal.add(Calendar.DAY_OF_YEAR, 1)
            targetTime = SolarTimeHelper.calculateSunsetMillis(lat, lon, cal) - (20 * 60 * 1000L)
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, GoldenHourReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            }
            Log.i(TAG, "Golden hour alarm scheduled for: ${java.util.Date(targetTime)}")
        } catch (e: Exception) {
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetTime, pendingIntent)
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to schedule golden hour alarm: ${ex.message}")
            }
        }
    }

    fun cancelGoldenHour(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, GoldenHourReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Golden hour alarm cancelled")
    }

    private fun getLastKnownLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            val providers = locationManager.getProviders(true)
            var bestLoc: Location? = null
            for (p in providers) {
                val loc = locationManager.getLastKnownLocation(p) ?: continue
                if (bestLoc == null || loc.accuracy < bestLoc.accuracy) {
                    bestLoc = loc
                }
            }
            bestLoc
        } catch (e: SecurityException) {
            null
        }
    }
}
