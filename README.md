# Lievis Cam

Ứng dụng chụp ảnh Android (Kotlin, CameraX) với AI gợi ý bố cục theo thời gian thực. Giao diện **Liquid Glass** — nền đen, accent hồng phấn (`#F8A9C4`). Mọi tính năng AI chạy **hoàn toàn on-device (offline, miễn phí 100%)**, không cần mạng, không cần tài khoản.

## Tính năng chính

### 1. AI gợi ý bố cục — 2 chế độ riêng biệt (mặc định TẮT mỗi lần mở app)
- **Gợi ý những điểm đẹp**: AI quét khung hình, hiện tối đa 4 chấm gợi ý; chạm vào chấm để tự động zoom tới bố cục đẹp nhất cho điểm đó.
- **Tự động zoom khung ảnh đẹp**: vòng vàng đích + mũi tên dẫn hướng; lia máy đúng vị trí → tự zoom mượt, rung nhẹ; tự chụp nếu đã bật "Tự động chụp" trong Công cụ.
- Model **YOLOv12n** on-device (~10MB), warmup GPU chống khựng khi bật AI.

### 2. Bộ lọc màu
- 7 filter cổ điển (Gốc, Sáng, Trầm, Đen trắng, Ấm, Lạnh, Film), áp trực tiếp vào ảnh khi lưu.
- **Tùy chỉnh** (kiểu iPhone): lưới chọn màu 2D + 3 thanh trượt TÔNG / ẤM / RỰC RỠ; nút mở nhanh luôn hiện ở cạnh trái màn hình; kéo tới đâu, màn hình chụp đổi màu tới đó (xem trước trực tiếp).
- AI gợi ý filter theo độ sáng khung hình và thời gian trong ngày.

### 3. Công cụ (panel ⋯)
Histogram realtime • Pose guide 6 dáng (đứng thẳng, ngồi, dựa nghiêng, tay giơ cao, bước đi, nửa người) • AI học gu • Nhắc giờ vàng • Thước cân bằng.

### 4. Chụp ảnh
- Nút chụp luôn chụp ngay, không bị AI chặn hay delay.
- Zoom tay 0.5x / 1x / 3x / 6x + pinch-to-zoom 2 ngón.
- Lưới 1/3 (bấm nhiều lần để đổi kiểu), tab Thư viện riêng, xem / chia sẻ / xóa ảnh.
- Tự động kiểm tra và cập nhật bản mới trong app từ GitHub Releases.

## Kỹ thuật
- Package `com.dinh.aicamera` • Kotlin 2.0.21 • AGP 8.7.3 • compileSdk 35 • minSdk 26
- CameraX • YOLOv12n (TFLite + GPU delegate) • ColorMatrix filter • RenderEffect live preview (API 31+)
- CI: build debug APK mỗi lần push; build & release APK khi push tag `v*`.

## Tải bản mới nhất
Vào mục **Releases** của repo để tải file `LievisCam-v2.0.3.apk` mới nhất.
