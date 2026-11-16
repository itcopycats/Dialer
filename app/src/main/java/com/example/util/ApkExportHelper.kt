package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ApkExportHelper {

  private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

  private fun getSourceApkFile(context: Context): File {
    val path = context.applicationInfo.publicSourceDir ?: context.applicationInfo.sourceDir
      ?: throw IllegalStateException("APK source path unavailable")
    val file = File(path)
    if (!file.exists()) {
      throw IllegalStateException("APK file not found")
    }
    return file
  }

  private fun getExportFileName(): String {
    val safeVersion = BuildConfig.VERSION_NAME.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return "FireLimit_v${safeVersion}.apk"
  }

  suspend fun prepareShareApkIntent(context: Context): Result<Intent> = withContext(Dispatchers.IO) {
    runCatching {
      val sourceFile = getSourceApkFile(context)
      val shareDir = File(context.cacheDir, "shared_apk").apply { mkdirs() }
      val destFile = File(shareDir, getExportFileName())
      sourceFile.inputStream().use { input ->
        destFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }

      val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        destFile
      )

      val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = APK_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "FireLimit v${BuildConfig.VERSION_NAME}")
        putExtra(Intent.EXTRA_TEXT, "FireLimit (${context.packageName})")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

      Intent.createChooser(shareIntent, "Share FireLimit APK").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }
  }

  suspend fun saveApkToInternalStorage(context: Context): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val sourceFile = getSourceApkFile(context)
      val fileName = getExportFileName()

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/FireLimit"

        // Remove any previous copy with the same name in Downloads/FireLimit to avoid (1) duplicates
        try {
          resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(fileName)
          )
        } catch (_: Exception) { }

        val values = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
          put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
          put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
          put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: throw IllegalStateException("Unable to create file in Downloads")

        try {
          resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { input ->
              input.copyTo(out)
            }
          } ?: throw IllegalStateException("Unable to write APK file")

          values.clear()
          values.put(MediaStore.MediaColumns.IS_PENDING, 0)
          resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
          try {
            resolver.delete(uri, null, null)
          } catch (_: Exception) { }
          throw t
        }

        "Download/FireLimit/$fileName"
      } else {
        @Suppress("DEPRECATION")
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(publicDownloads, "FireLimit")
        val canWritePublic = (targetDir.exists() || targetDir.mkdirs()) && targetDir.canWrite()
        val destDir = if (canWritePublic) {
          targetDir
        } else {
          File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "FireLimit").apply {
            mkdirs()
          }
        }
        val destFile = File(destDir, fileName)
        sourceFile.inputStream().use { input ->
          destFile.outputStream().use { output ->
            input.copyTo(output)
          }
        }
        destFile.absolutePath
      }
    }
  }
}
