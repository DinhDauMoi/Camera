package com.dinh.aicamera.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.dinh.aicamera.BuildConfig
import com.dinh.aicamera.ui.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val latestVersion: String,
    val changelog: String,
    val downloadUrl: String,
    val isNewer: Boolean
)

sealed class UpdateResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateResult()
    object AlreadyLatest : UpdateResult()
    object NoReleasesFound : UpdateResult() // 404
    object RateLimited : UpdateResult() // 403
    object NoApkAttached : UpdateResult() // Không có file .apk trong assets
    data class NetworkError(val message: String) : UpdateResult()
    data class UnknownError(val message: String) : UpdateResult()
}

class AppUpdateManager(private val context: Context) {

    private val preferences = AppPreferences(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun checkUpdate(isManual: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        if (!isManual && !preferences.isAutoCheckUpdate) {
            return@withContext UpdateResult.AlreadyLatest
        }

        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> {
                        Log.e("AppUpdateManager", "GitHub 404: Chưa có bản phát hành nào trong repo ${BuildConfig.GITHUB_REPO_OWNER}/${BuildConfig.GITHUB_REPO_NAME}")
                        return@withContext UpdateResult.NoReleasesFound
                    }
                    403 -> {
                        Log.e("AppUpdateManager", "GitHub 403: Rate limit exceeded (vượt giới hạn request không xác thực)")
                        return@withContext UpdateResult.RateLimited
                    }
                    in 200..299 -> {
                        // Tiếp tục xử lý
                    }
                    else -> {
                        Log.e("AppUpdateManager", "GitHub API phản hồi HTTP ${response.code}: ${response.message}")
                        return@withContext UpdateResult.UnknownError("HTTP ${response.code}: ${response.message}")
                    }
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.e("AppUpdateManager", "Nội dung phản hồi từ GitHub rỗng")
                    return@withContext UpdateResult.UnknownError("Phản hồi rỗng")
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val cleanLatest = tagName.removePrefix("v").removePrefix("V")
                val cleanCurrent = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")
                val changelog = json.optString("body", "Cập nhật tính năng mới và sửa lỗi")

                // Tìm file APK trong assets
                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                if (apkUrl.isEmpty()) {
                    Log.e("AppUpdateManager", "Release $tagName không có tệp .apk đính kèm trong assets")
                    return@withContext UpdateResult.NoApkAttached
                }

                val isNewer = isVersionNewer(cleanLatest, cleanCurrent)
                if (!isNewer) {
                    Log.d("AppUpdateManager", "Phiên bản hiện tại $cleanCurrent đã là mới nhất so với $cleanLatest")
                    return@withContext UpdateResult.AlreadyLatest
                }

                // Nếu tự động kiểm tra mà phiên bản này user đã chọn "Để sau" trước đó -> bỏ qua
                if (!isManual && cleanLatest == preferences.ignoredUpdateVersion) {
                    return@withContext UpdateResult.AlreadyLatest
                }

                return@withContext UpdateResult.UpdateAvailable(
                    UpdateInfo(
                        latestVersion = tagName,
                        changelog = changelog,
                        downloadUrl = apkUrl,
                        isNewer = true
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Lỗi ngoại lệ khi checkUpdate", e)
            return@withContext UpdateResult.NetworkError(e.localizedMessage ?: "Lỗi kết nối mạng")
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("AppUpdateManager", "Tải APK thất bại: HTTP ${response.code} ${response.message}")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val contentLength = body.contentLength()

                val updateDir = File(context.cacheDir, "updates")
                if (!updateDir.exists()) updateDir.mkdirs()
                val apkFile = File(updateDir, "update_${System.currentTimeMillis()}.apk")

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    if (contentLength > 0) {
                        val progress = ((totalBytesRead * 100) / contentLength).toInt()
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()
                return@withContext apkFile
            }
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Lỗi tải tệp APK từ $downloadUrl", e)
            return@withContext null
        }
    }

    fun openInstallApk(apkFile: File) {
        if (!apkFile.exists()) {
            Log.e("AppUpdateManager", "Tệp APK cần cài đặt không tồn tại: ${apkFile.absolutePath}")
            return
        }

        // Kiểm tra quyền REQUEST_INSTALL_PACKAGES trên Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Log.w("AppUpdateManager", "Chưa cấp quyền cài đặt ứng dụng không rõ nguồn gốc")
                Toast.makeText(
                    context,
                    "Vui lòng cho phép cài đặt ứng dụng từ nguồn này để tiếp tục cập nhật",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("AppUpdateManager", "Lỗi khi gọi intent mở trình cài đặt APK", e)
            Toast.makeText(
                context,
                "Lỗi mở cài đặt: ${e.localizedMessage}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until length) {
            val v1 = latestParts.getOrElse(i) { 0 }
            val v2 = currentParts.getOrElse(i) { 0 }
            if (v1 > v2) return true
            if (v1 < v2) return false
        }
        return false
    }
}
