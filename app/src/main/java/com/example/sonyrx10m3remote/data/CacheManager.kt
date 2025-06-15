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
        private const val METADATA_FILE = "metadata.json"
    }

    private fun getCameraCacheDir(cameraId: String): File {
        val dir = File(context.cacheDir, "SonyRX10M3Remote/cache/$cameraId")
//        Log.d("CacheManager", "Camera cache dir path = ${dir.absolutePath}")
        if (!dir.exists()) dir.mkdirs().also {
            Log.d("CacheManager", "mkdirs() returned $it for ${dir.absolutePath}")
        }
        return dir
    }

    private fun getMetadataDir(cameraId: String): File {
        val dir = File(getCameraCacheDir(cameraId), "metadata")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ---------------- Cached Metadata ----------------

    suspend fun saveCachedMetadata(
        cameraId: String,
        capturedImages: List<CapturedImage>
    ) = withContext(Dispatchers.IO) {
        val metadataFile = File(getMetadataDir(cameraId), METADATA_FILE)

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
                    put("localUri", image.localUri?.toString())  // Save localUri as string or null
                }
                jsonArray.put(obj)
            }

            FileWriter(metadataFile).use { it.write(jsonArray.toString()) }

            Log.d(TAG, "Saved metadata for ${capturedImages.size} images to $metadataFile")

        } catch (e: IOException) {
            Log.e(TAG, "Error writing metadata.json: ${e.localizedMessage}")
        }
    }

    suspend fun loadCachedMetadata(cameraId: String): List<CapturedImage> = withContext(Dispatchers.IO) {
        val metadataFile = File(getMetadataDir(cameraId), METADATA_FILE)

        if (!metadataFile.exists()) {
            Log.w(TAG, "No cached metadata found at ${metadataFile.path}")
            return@withContext emptyList()
        }

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
                        localUri = obj.optString("localUri", null)?.let { Uri.parse(it) }  // parse localUri if exists
                    )
                )
            }

            Log.d(TAG, "Loaded ${images.size} images from cache for camera $cameraId")
            return@withContext images

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached metadata: ${e.localizedMessage}")
            return@withContext emptyList()
        }
    }

    // ---------------- Cached Thumbnails ----------------

    fun getThumbnailFile(cameraId: String, imageUri: String): File {
        // sanitize imageUri into a safe name via SHA-256
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(imageUri.toByteArray(Charsets.UTF_8))
        val safeName = hashBytes.joinToString("") { "%02x".format(it) }
        val dir = getThumbnailDir(cameraId)
        return File(dir, "thumb_$safeName.jpg")
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

            return@withContext resultUri

        } catch (e: Exception) {
            Log.e(TAG, "Error downloading thumbnail: ${e.localizedMessage}")
            return@withContext null
        }
    }

    fun isThumbnailCached(cameraId: String, imageUri: String): Boolean {
        val file = getThumbnailFile(cameraId, imageUri)
        return file.exists() && file.length() > 0
    }

   fun getThumbnailDir(cameraId: String): File {
        val dir = File(getCameraCacheDir(cameraId), "thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ----------------

    fun clearCache(cameraId: String): Boolean {
        val dir = getCameraCacheDir(cameraId)
        return dir.deleteRecursively().also {
            Log.d(TAG, "Cache for camera $cameraId cleared: $it")
        }
    }

//    fun cleanOldCacheLocation() {
//        val oldCache = File(context.getExternalFilesDir(null), "cache")
//        if (oldCache.exists()) {
//            oldCache.deleteRecursively()
//            Log.i("CacheMigration", "Old external cache directory deleted.")
//        }
//    }
}