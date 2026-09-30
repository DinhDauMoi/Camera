package com.dinh.aicamera.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

object SolarTimeHelper {
    /**
     * Tính toán thời điểm hoàng hôn (sunset epoch millis) dựa trên tọa độ vĩ độ (lat), kinh độ (lon)
     * và ngày trong năm theo công thức thiên văn NOAA chuẩn (Sunset Hour Angle).
     */
    fun calculateSunsetMillis(lat: Double, lon: Double, calendar: Calendar): Long {
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)
        val year = calendar.get(Calendar.YEAR)

        // Fractional year (radians)
        val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1)

        // Equation of time (phút)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))

        // Solar declination (radians)
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        // Góc thiên đỉnh hoàng hôn: 90.833 độ (bao gồm hiệu ứng khúc xạ khí quyển)
        val zenithRad = Math.toRadians(90.833)
        val latRad = Math.toRadians(lat)

        val cosHourAngle = (cos(zenithRad) - sin(latRad) * sin(decl)) / (cos(latRad) * cos(decl))
        val hourAngleDeg = when {
            cosHourAngle > 1.0 -> 0.0 // Đêm vùng cực (không có hoàng hôn)
            cosHourAngle < -1.0 -> 180.0 // Ngày vùng cực (mặt trời không lặn)
            else -> Math.toDegrees(acos(cosHourAngle))
        }

        // Thời điểm hoàng hôn tính bằng phút UTC tính từ 00:00 UTC
        val sunsetUtcMinutes = 720.0 - 4.0 * lon - eqTime + hourAngleDeg * 4.0

        val calUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.DAY_OF_YEAR, dayOfYear)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, sunsetUtcMinutes.roundToInt())
        }

        return calUtc.timeInMillis
    }
}
