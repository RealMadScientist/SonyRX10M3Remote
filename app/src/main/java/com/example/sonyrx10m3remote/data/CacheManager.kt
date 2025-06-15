package com.example.sonyrx10m3remote.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException

class CacheManager(private val context: Context) {

    companion object {
        private const val TAG = "CacheManager"
        private const val BASE_DIR = "DCIM/SonyRX10M3Remote/cache"
        private const val DATE_INDEX_FILE = "dates.json"
    }

    private fun getCameraCacheDir(cameraId: String): File {
        val dir = File(context.cacheDir, "$BASE_DIR/$cameraId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getThumbnailDir(cameraId: String): File {
        val dir = File(getCameraCacheDir(cameraId), "thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getDateMetadataDir(cameraId: String, dateKey: String): File {
        val dir = File(getCameraCacheDir(cameraId), "metadata/$dateKey")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getDateIndexFile(cameraId: String): File {
        return File(getCameraCacheDir(cameraId), "metadata/$DATE_INDEX_FILE")
    }

    // ------------------ Date Index ------------------

    suspend fun saveKnownDates(cameraId: String, dates: List<String>) = withContext(Dispatchers.IO) {
        val file = getDateIndexFile(cameraId)
        try {
            file.parentFile?.mkdirs() // <--- create parent directories if they don't exist
            FileWriter(file).use { it.write(JSONArray(dates).toString()) }
            Log.d(TAG, "Saved ${dates.size} known dates to ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write dates index: ${e.localizedMessage}")
        }
    }

    suspend fun loadKnownDates(cameraId: String): List<String> = withContext(Dispatchers.IO) {
        val file = getDateIndexFile(cameraId)
        if (!file.exists()) return@withContext emptyList()
        return@withContext try {
            val jsonArray = JSONArray(file.readText())
            List(jsonArray.length()) { jsonArray.getString(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load date index: ${e.localizedMessage}")
            emptyList()
        }
    }

    // ------------------ Per-Date Metadata ------------------

    suspend fun saveCachedMetadataForDate(
        cameraId: String,
        dateKey: String,
        capturedImages: List<CapturedImage>
    ) = withContext(Dispatchers.IO) {
        val metadataFile = File(getDateMetadataDir(cameraId, dateKey), "metadata_$dateKey.json")
        metadataFile.parentFile?.mkdirs()
        try {
            val jsonArray = JSONArray()
            for (image in capturedImages) {
                val obj = JSONObject().apply {
                    put("uri", image.uri)
                    put("remoteUrl", image.remoteUrl)
                    put("thumbnailUrl", image.thumbnailUrl)
                    put("fileName", image.fileName)
                    put("timestamp", image.timestamp)
                    put("downloaded", image.downloaded)
                    put("contentKind", image.contentKind)
                    put("lastModified", image.lastModified)
                    put("localUri", image.localUri?.toString())
                }
                jsonArray.put(obj)
            }

            FileWriter(metadataFile).use { it.write(jsonArray.toString()) }
            Log.d(TAG, "Saved metadata for date $dateKey (${capturedImages.size} items) to ${metadataFile.absolutePath}")

        } catch (e: IOException) {
            Log.e(TAG, "Error writing metadata for date $dateKey: ${e.localizedMessage}")
        }
    }

    suspend fun loadCachedMetadataForDate(cameraId: String, dateKey: String): List<CapturedImage> = withContext(Dispatchers.IO) {
        val metadataFile = File(getDateMetadataDir(cameraId, dateKey), "metadata_$dateKey.json")
        if (!metadataFile.exists()) return@withContext emptyList()

        try {
            val json = metadataFile.readText()
            val jsonArray = JSONArray(json)
            val images = mutableListOf<CapturedImage>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                images.add(
                    CapturedImage(
                        uri = obj.getString("uri"),
                        remoteUrl = obj.optString("remoteUrl", null),
                        thumbnailUrl = obj.optString("thumbnailUrl", null),
                        fileName = obj.optString("fileName", null),
                        timestamp = obj.optLong("timestamp", 0L),
                        downloaded = obj.optBoolean("downloaded", false),
                        contentKind = obj.optString("contentKind", null),
                        lastModified = obj.optLong("lastModified", System.currentTimeMillis()),
                        localUri = obj.optString("localUri", null)?.let { Uri.parse(it) }
                    )
                )
            }

            Log.d(TAG, "Loaded ${images.size} items from cache for date $dateKey")
            images

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load metadata for date $dateKey: ${e.localizedMessage}")
            emptyList()
        }
    }

    // ------------------ Thumbnails ------------------

    fun getThumbnailFile(cameraId: String, imageUri: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(imageUri.toByteArray(Charsets.UTF_8))
        val safeName = hashBytes.joinToString("") { "%02x".format(it) }
        return File(getThumbnailDir(cameraId), "thumb_$safeName.jpg")
    }

    suspend fun downloadAndSaveThumbnail(
        cameraId: String,
        imageUri: String,
        thumbnailUrl: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val file = getThumbnailFile(cameraId, imageUri)
            if (file.exists() && file.length() > 0) {
                return@withContext Uri.fromFile(file)
            }

            var resultUri: Uri? = null

            withTimeout(5000L) {
                val client = OkHttpClient()
                val request = Request.Builder().url(thumbnailUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Thumbnail download failed: $thumbnailUrl, code=${response.code}")
                        return@use
                    }

                    response.body?.let { body ->
                        file.parentFile?.mkdirs()
                        file.outputStream().use { os ->
                            os.write(body.bytes())
                        }
                        resultUri = Uri.fromFile(file)
                    }
                }
            }

            resultUri

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading thumbnail: ${e.localizedMessage}")
            null
        }
    }

    fun isThumbnailCached(cameraId: String, imageUri: String): Boolean {
        val file = getThumbnailFile(cameraId, imageUri)
        return file.exists() && file.length() > 0
    }

    // ------------------ Cache Clear ------------------

    fun clearCache(cameraId: String): Boolean {
        val dir = getCameraCacheDir(cameraId)
        return dir.deleteRecursively().also {
            Log.d(TAG, "Cache for camera $cameraId cleared: $it")
        }
    }
}