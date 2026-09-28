# AI Camera - Realtime AI Composition Guide (Doka Cam Style)

Ứng dụng Android Native (Kotlin) chụp ảnh tối giản với AI hướng dẫn bố cục theo thời gian thực (kiểu Doka Cam / Leica), giao diện Liquid Glass (kính mờ trong suốt, blur nền, bo góc mượt mà). Tất cả tính năng chạy **hoàn toàn On-Device (Offline, Miễn phí 100%)**, không cần mạng, không cần tài khoản, không cần API server trả phí.

---

## 📸 Tính năng chính

### 1. AI Composition Guide (Realtime AR)
* **Lưới 1/3 (Rule of Thirds)**: Vẽ nét mỏng tinh tế, không che khuất chủ thể.
* **Vòng tròn vàng (Target Ring)**: Đánh dấu giao điểm 1/3 lý tưởng gần chủ thể nhất (ưu tiên đường 1/3 tầm mắt nếu phát hiện chân dung người).
* **Mũi tên AR & Nhận diện chủ thể**:
  * Chạy Google ML Kit on-device (`face-detection` ưu tiên mặt người, fallback `object-detection` stream mode tìm vật thể).
  * Mũi tên hướng dẫn điều hướng camera về vị trí vàng.
* **Thước chân trời (Horizon Level)**: Đo độ cân bằng xoay nghiêng (roll/pitch) thông qua cảm biến `Rotation Vector`.
* **Chấm điểm bố cục (0 – 100)**:
  * Quy tắc tính điểm rule-based: gần giao điểm 1/3 (+ điểm), máy cân bằng thẳng thớm (+ điểm).
  * Phạt điểm khi chủ thể bị cắt viền mép hình, chủ thể đặt chết ở chính giữa (dead-center), hoặc chủ thể quá nhỏ/quá to.
* **Gợi ý tiếng Việt**: *"Dịch máy sang trái"*, *"Dịch máy sang phải"*, *"Hạ thấp máy xuống"*, *"Nâng máy lên cao"*, *"Giữ máy thẳng"*, *"Tiến lại gần chủ thể hơn"*, *"Bố cục hoàn hảo! Giữ yên để chụp"*.
* **Tự động chụp (Auto Capture)**: Khi điểm bố cục $\ge 85$ được duy trì liên tục trong 1 giây $\to$ kích hoạt rung nhẹ (Haptic feedback) + tự động chụp ảnh.

### 2. Bộ lọc ảnh (ColorMatrix Filter) & AI Gợi ý
* 6 bộ lọc màu phong cách (không cần thư viện nặng, nướng thẳng vào ảnh khi lưu):
  * **Gốc**: Màu sắc trung thực của cảm biến.
  * **Sáng (Bright)**: Nâng sáng, tăng sức sống trong điều kiện thiếu sáng.
  * **Trầm (Muted)**: Giảm độ bão hòa, bóng đổ sâu, phong cách moody.
  * **Đen trắng (B&W)**: Tương phản cao hoài niệm.
  * **Ấm (Warm)**: Ánh nắng chiều vàng (Golden Hour).
  * **Lạnh (Cool)**: Tone xanh lạnh điện ảnh (Cinematic Cool).
  * **Film**: Nâng màu shadow, chất màu máy phim analog cổ điển.
* **AI Gợi ý Filter**: Tự động phân tích độ sáng trung bình khung hình (Y-plane) kết hợp thời gian thực (ví dụ thiếu sáng/đêm $\to$ gợi ý Sáng; nắng gắt $\to$ gợi ý Trầm; hoàng hôn $\to$ gợi ý Ấm).

### 3. Thao tác Camera & MediaStore
* Chụp thủ công, lật camera trước / sau, chuyển chế độ flash (Tự động / Bật / Tắt).
* Lưu ảnh chất lượng cao vào thư viện hệ thống qua `MediaStore.Images` (thư mục `Pictures/AICamera`), tương thích Android 10+ không cần xin quyền lưu trữ nguy hiểm.
* Xem lại ảnh vừa chụp trực tiếp trong ứng dụng: Liquid Glass Bottom Sheet với cử chỉ vuốt ngang xem các ảnh đã lưu.

### 4. Giao diện Liquid Glass (iOS 26 Style)
* Nền kính mờ trong suốt, viền sáng mỏng `1px`, hiệu ứng chiều sâu và đổ bóng nhẹ.
* Sử dụng `RenderEffect.createBlurEffect` trên Android 12+ (API 31+); tự động fallback nền mờ cho thiết bị cũ.
* 100% bo góc (tối thiểu 20-28dp, thanh công cụ và nút bấm dạng capsule/pill bo tròn hoàn toàn).
* 1 màu Accent duy nhất: **Golden Amber (`#FFB800`)**.

---

## 📂 Cấu trúc Package (`com.dinh.aicamera`)

```text
app/src/main/java/com/dinh/aicamera/
├── camera/
│   ├── CameraManager.kt          # Điều khiển CameraX (Preview, Capture, Analysis, Flip, Flash, MediaStore)
│   └── FrameAnalyzer.kt          # Phân tích frame trên background thread (ML Kit Face + Object, Luminance)
├── composition/
│   ├── CompositionEngine.kt      # Chấm điểm 0-100, xác định giao điểm 1/3, tính toán auto-capture 1s, câu nhắc tiếng Việt
│   ├── CompositionState.kt       # Model dữ liệu trạng thái bố cục
│   └── SensorOrientationHelper.kt# Đọc cảm biến xoay đo góc nghiêng chân trời (Roll/Pitch)
├── filter/
│   ├── FilterType.kt             # Danh mục filter (Sáng, Trầm, Đen trắng, Ấm, Lạnh, Film)
│   ├── ColorMatrixFilter.kt      # Ma trận biến đổi màu ColorMatrix & xử lý trực tiếp lên Bitmap
│   └── AIFilterRecommender.kt    # Gợi ý filter dựa trên độ sáng và thời gian chụp
├── overlay/
│   └── CompositionOverlayView.kt # Custom View vẽ AR mượt mà 60fps (lưới 1/3, vòng đích vàng, mũi tên, thước chân trời, vòng điểm)
└── ui/
    ├── MainActivity.kt           # Màn hình chính điều phối tương tác, xin quyền Camera, rung phản hồi
    ├── LiquidGlassHelper.kt      # RenderEffect real-time blur cho Android 12+
    ├── GalleryAdapter.kt         # Adapter vuốt ảnh qua ViewPager2
    └── GalleryBottomSheetDialog.kt # Hộp thoại xem ảnh Liquid Glass
```

---

## 🛠 Cách chạy thử và Build

### 1. Mở trong Android Studio
1. Mở Android Studio $\to$ **Open** $\to$ Chọn thư mục `Camera`.
2. Chờ Gradle đồng bộ (Sync Project with Gradle Files).
3. Kết nối điện thoại Android (Android 8.0 / API 26 trở lên) hoặc khởi động Android Emulator có hỗ trợ Camera.
4. Nhấn **Run (Shift + F10)**.

### 2. Build bằng dòng lệnh (Gradle)
```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
chmod +x gradlew
./gradlew assembleDebug
```
File APK xuất ra tại: `app/build/outputs/apk/debug/app-debug.apk`.

### 3. CI/CD Tự động hóa
Workflow GitHub Actions đã được tích hợp tại `.github/workflows/build.yml`. Mỗi khi push code lên nhánh `main`, hệ thống sẽ tự động kích hoạt máy chủ Ubuntu, cài đặt JDK 17, build APK và đính kèm artifact `aicamera-debug-apk` để tải về trực tiếp.
