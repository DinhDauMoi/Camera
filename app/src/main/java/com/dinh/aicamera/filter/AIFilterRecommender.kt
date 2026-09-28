package com.dinh.aicamera.filter

import java.util.Calendar

data class FilterRecommendation(
    val filterType: FilterType,
    val message: String
)

object AIFilterRecommender {

    /**
     * Gợi ý filter dựa trên độ sáng trung bình (0..255) và thời điểm chụp
     */
    fun recommend(
        averageLuminance: Float,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): FilterRecommendation {
        return when {
            // Trường hợp thiếu sáng hoặc ban đêm -> Gợi ý Sáng
            averageLuminance < 65f || hourOfDay >= 19 || hourOfDay <= 5 -> {
                FilterRecommendation(
                    filterType = FilterType.BRIGHT,
                    message = "AI: Khung hình tối, gợi ý filter Sáng"
                )
            }
            // Trường hợp nắng gắt (chói sáng) -> Gợi ý Trầm để hạ bớt gắt
            averageLuminance > 185f -> {
                FilterRecommendation(
                    filterType = FilterType.MUTED,
                    message = "AI: Ánh sáng gắt, gợi ý filter Trầm dịu mắt"
                )
            }
            // Khung giờ hoàng hôn (16h - 18h) -> Gợi ý Ấm
            hourOfDay in 16..18 -> {
                FilterRecommendation(
                    filterType = FilterType.WARM,
                    message = "AI: Giờ vàng hoàng hôn, gợi ý filter Ấm"
                )
            }
            // Khung giờ sáng sớm (6h - 8h) -> Gợi ý Lạnh hoặc Film
            hourOfDay in 6..8 -> {
                FilterRecommendation(
                    filterType = FilterType.COOL,
                    message = "AI: Không khí sớm mai trong lành, gợi ý filter Lạnh"
                )
            }
            // Ánh sáng hài hòa thông thường -> Gợi ý Film nghệ thuật
            else -> {
                FilterRecommendation(
                    filterType = FilterType.FILM,
                    message = "AI: Ánh sáng đẹp, gợi ý filter Film cổ điển"
                )
            }
        }
    }
}
