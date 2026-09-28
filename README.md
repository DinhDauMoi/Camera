# AI Camera - Realtime AI Composition Guide (Doka Cam Style)

Ứng dụng Android Native (Kotlin) chụp ảnh tối giản với AI hướng dẫn bố cục theo thời gian thực (kiểu Doka Cam / Leica), giao diện **Liquid Glass** (kính mờ trong suốt, blur nền, bo góc mượt mà, không góc nhọn). Tất cả tính năng AI chạy **hoàn toàn On-Device (Offline, Miễn phí 100%)**, không cần mạng, không cần tài khoản, không cần API server trả phí.

---

## 📸 Tính năng chính

### 1. AI Composition Guide (AI Hướng dẫn Bố cục)
* **AI mặc định TẮT**: Khi mở app là camera thường, preview sạch sẽ, không quét khung hình, không tốn pin.
* **Nút bật/tắt AI trên thanh công cụ glass pill**:
  * Chuyển đổi trạng thái trực quan (icon chuyển màu vàng ánh kim khi bật).
  * **Bật AI**: Bắt đầu quét khung hình bằng Google ML Kit (`face-detection` ưu tiên người, fallback `object-detection` stream mode tìm vật thể) + kích hoạt engine chấm điểm rule-based và AR overlay:
    * Lưới 1/3 mờ (tự động hiện khi bật AI).
    * Vòng tròn vàng (vị trí đích lý tưởng gần chủ thể nhất).
    * Mũi tên AR & gợi ý tiếng Việt: *"dịch máy sang trái"*, *"hạ thấp máy xuống"*, *"giữ máy thẳng"*, *"tiến lại gần hơn"*...
    * Vòng điểm bố cục (0–100) ở góc trên màn hình.
  * **Tắt AI**: Dừng quét ML Kit ngay lập tức để tiết kiệm pin, ẩn toàn bộ AR, quay về giao diện camera sạch.
* **Nút chụp luôn chụp ngay**: Không bị AI chặn hay delay dù đang quét.
* **Tự động chụp (Auto Capture)**: Chỉ kích hoạt khi AI đang BẬT và điểm bố cục $> 85$ duy trì liên tục trong 1 giây $\to$ rung nhẹ (Haptic pulse) + tự động chụp.

### 2. Bộ lọc ảnh (ColorMatrix Filter) & AI Gợi ý
* 6 bộ lọc phong cách cổ điển (dùng `ColorMatrix`, nướng trực tiếp vào ảnh khi lưu):
  * **Gốc**: Màu sắc tự nhiên.
  * **Sáng (Bright)**: Nâng sáng, tăng sức sống cho ảnh thiếu sáng.
  * **Trầm (Muted)**: Tone màu chiều sâu trầm ấm, moody.
  * **Đen trắng (B&W)**: Tương phản cao hoài niệm.
  * **Ấm (Warm)**: Ánh nắng hoàng hôn (Golden Hour).
  * **Lạnh (Cool)**: Tone xanh lạnh điện ảnh.
  * **Film**: Nâng sáng vùng tối, chất màu film analog retro.
* **AI gợi ý filter (rule-based)**: Đo độ sáng trung bình khung hình (Y-plane) kết hợp thời gian chụp trong ngày $\to$ hiện bóng gợi ý Liquid Glass để user kích hoạt nhanh bằng 1 chạm.

### 3. Toggle Lưới 1/3 & Tab Thư viện (Gallery) riêng
* **Toggle Lưới 1/3**: Nút bật/tắt lưới riêng trên thanh công cụ glass pill, lưu trạng thái vào Preferences.
* **Tab Gallery riêng (Bottom navigation pill)**:
  * Chuyển đổi giữa 2 tab: **Máy ảnh** và **Thư viện**.
  * Lưới thumbnail 3 cột hiển thị ảnh đã chụp từ `MediaStore` (thư mục `Pictures/AICamera`), tất cả thumbnail bo góc tròn 18dp.
  * Chạm vào ảnh để xem toàn màn hình (Fullscreen Viewer) với cử chỉ vuốt ngang xem các ảnh.
  * **Nút Chia sẻ**: Chia sẻ ảnh qua ứng dụng khác với `ACTION_SEND`.
  * **Nút Xóa ảnh**: Xóa ảnh an toàn qua MediaStore, hỗ trợ đầy đủ `RecoverableSecurityException` (Android 10+) và `createDeleteRequest` (Android 11+) với dialog xác nhận của hệ thống.

### 4. Tự động Cập nhật trong App (Kiểu JAVIS)
* Kiểm tra phiên bản mới từ GitHub Releases API:
  `https://api.github.com/repos/DinhDauMoi/Camera/releases/latest`
* So sánh `tag_name` với `versionName` trong app:
  * Có bản mới $\to$ hiển thị dialog Liquid Glass (bo góc 24dp) hiển thị changelog và nút **Cập nhật ngay**.
  * Tải file APK trực tiếp về cache với thanh tiến trình % $\to$ mở trình cài đặt hệ thống (`FileProvider` + `ACTION_VIEW`, quyền `REQUEST_INSTALL_PACKAGES`).
* Có toggle *"Tự động kiểm tra bản cập nhật"* trong màn hình Cài đặt (mặc định BẬT, ghi nhớ version đã bỏ qua để tránh làm phiền).

### 5. Giao diện (UI/UX) — Liquid Glass (iOS 26 Style)
* Kính mờ trong suốt, blur nền thời gian thực (`RenderEffect.createBlurEffect` trên Android 12+, fallback nền mờ cho máy cũ).
* Tuyệt đối không góc nhọn: bo tròn capsule/pill hoàn toàn hoặc bo góc 18–26dp.
* Nền tối sang trọng, chữ trắng, 1 màu Accent duy nhất: **Golden Amber (`#FFB800`)**.

---

## 📂 Cấu trúc Package (`com.dinh.aicamera`)

```text
app/src/main/java/com/dinh/aicamera/
├── camera/
│   ├── CameraManager.kt          # CameraX (Preview, Capture, Analysis, Flip, Flash, Filter & MediaStore)
│   └── FrameAnalyzer.kt          # Phân tích frame ML Kit on-device (tự động bỏ qua khi AI tắt)
├── composition/
│   ├── CompositionEngine.kt      # Chấm điểm 0-100, xác định giao điểm 1/3, đếm 1s auto-capture
│   ├── CompositionState.kt       # Trạng thái bố cục, AR target, góc nghiêng
│   └── SensorOrientationHelper.kt# Đo góc nghiêng chân trời (Rotation Vector sensor)
├── filter/
│   ├── FilterType.kt             # 6 bộ lọc ColorMatrix
│   ├── ColorMatrixFilter.kt      # ColorMatrix và xử lý áp trực tiếp lên Bitmap
│   └── AIFilterRecommender.kt    # Gợi ý filter dựa trên độ sáng và giờ chụp
├── overlay/
│   └── CompositionOverlayView.kt # Custom View AR (lưới 1/3, target vàng, mũi tên, thước chân trời, vòng điểm)
└── ui/
    ├── MainActivity.kt           # Màn hình chính điều phối tab Camera & Gallery, AI toggle, permissions
    ├── AppPreferences.kt         # Lưu trạng thái AI toggle, grid toggle, auto-update
    ├── LiquidGlassHelper.kt      # RenderEffect real-time blur cho Android 12+
    ├── SettingsBottomSheetDialog.kt # Hộp thoại cài đặt tự cập nhật và thông tin version
    ├── gallery/
    │   ├── GalleryGridAdapter.kt   # Adapter lưới ảnh bo góc 18dp
    │   └── FullscreenPhotoDialog.kt# Trình xem ảnh full, vuốt ngang, nút chia sẻ và xóa an toàn
    └── update/
        ├── AppUpdateManager.kt    # Gọi GitHub Releases API, tải APK và mở FileProvider cài đặt
        └── UpdateDialogFragment.kt# Dialog Liquid Glass hiển thị changelog và tiến trình tải
```

---

## 🛠 CI/CD Workflows (GitHub Actions)

1. **Build Debug APK khi push lên `main`** ([.github/workflows/build.yml](file:///c:/Users/Admin/Desktop/Camera/.github/workflows/build.yml)):
   * Tự động checkout, cài JDK 17, build `./gradlew assembleDebug` và upload artifact `aicamera-debug-apk`.
2. **Tạo GitHub Release khi push tag** ([.github/workflows/release.yml](file:///c:/Users/Admin/Desktop/Camera/.github/workflows/release.yml)):
   * Khi push tag (vd `v1.0.1`): tự động build APK, tạo GitHub Release với release notes, đính kèm file APK vào release để app tự động kiểm tra và cập nhật.
