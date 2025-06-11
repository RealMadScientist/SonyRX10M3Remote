package com.example.sonyrx10m3remote.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

class MediaStoreHelper(private val context: Context) {
    private val TAG = "MediaStoreHelper"

    suspend fun downloadImage(url: String, filename: String): Uri? =
        withContext(Dispatchers.IO) {
            try {
                val mimeType = "image/jpeg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SonyRX10M3Remote")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Images.Media.getContentUri("external")

                val imageUri = resolver.insert(collection, contentValues)
                if (imageUri == null) {
                    Log.e(TAG, "Failed to create MediaStore entry.")
                    return@withContext null
                }

                resolver.openOutputStream(imageUri).use { outputStream ->
                    if (outputStream == null) {
                        Log.e(TAG, "Failed to get output stream.")
                        return@withContext null
                    }

                    URL(url).openStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Mark the file as no longer pending
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)

                Log.d(TAG, "Image saved to MediaStore: $imageUri")
                imageUri
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                null
            }
        }

    suspend fun loadDownloadedImages(folderRelativePath: String = "DCIM/SonyRX10M3Remote/"): List<Uri> =
        withContext(Dispatchers.IO) {
            val imageUris = mutableListOf<Uri>()
            val resolver = context.contentResolver

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH
            )

            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(folderRelativePath)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri =
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    imageUris.add(contentUri)
                }
            }

            return@withContext imageUris
        }

    suspend fun getUriForFilename(
        filename: String,
        folderRelativePath: String = "DCIM/SonyRX10M3Remote/"
    ): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(filename, folderRelativePath)
        val uriExternal = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        resolver.query(uriExternal, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idCol)
                return@withContext ContentUris.withAppendedId(uriExternal, id)
            }
        }
        null
    }
}