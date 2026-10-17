package com.jarves.mh.enhanced

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.jarves.mh.runtime.AndroidAppInstaller
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class OpenWithConfig(
    val mimeType: String,
    val isApk: Boolean,
    val allowWrite: Boolean,
)

data class EnhancedExportProgress(
    val fileName: String?,
    val message: String?,
    val progress: Float,
    val bytes: Pair<Long, Long>?,
)

object EnhancedFileOperations {

    fun resolveWorkspaceFile(workspaceRoot: File, relativePath: String): File {
        val root = workspaceRoot.canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "Path outside project workspace" }
        return file
    }

    fun mimeTypeForFileName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "*/*"
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "zst" -> "application/zstd"
            "json" -> "application/json"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "html", "htm" -> "text/html"
            "xml" -> "text/xml"
            "txt", "md", "kt", "java", "py", "sh", "c", "cpp", "h", "gradle", "properties", "js", "ts" -> "text/plain"
            else -> "*/*"
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "%.1f KB".format(java.util.Locale.US, kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return "%.1f MB".format(java.util.Locale.US, mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(java.util.Locale.US, gb)
    }

    fun determineOpenWithConfig(name: String): OpenWithConfig {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "apk" -> OpenWithConfig(
                mimeType = "application/vnd.android.package-archive",
                isApk = true,
                allowWrite = false,
            )
            "png", "jpg", "jpeg", "webp", "gif" -> OpenWithConfig(
                mimeType = "image/*",
                isApk = false,
                allowWrite = false,
            )
            "pdf" -> OpenWithConfig(
                mimeType = "application/pdf",
                isApk = false,
                allowWrite = false,
            )
            "zip", "tar", "gz", "zst" -> OpenWithConfig(
                mimeType = mimeTypeForFileName(name),
                isApk = false,
                allowWrite = false,
            )
            else -> OpenWithConfig(
                mimeType = mimeTypeForFileName(name),
                isApk = false,
                allowWrite = true,
            )
        }
    }

    fun exportSingleFile(
        context: Context,
        file: File,
        destinationUri: Uri,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long {
        require(file.isFile) { "Target is not a regular file: ${file.name}" }
        val fileLength = file.length()
        val resolver = context.contentResolver
        var bytesWritten = 0L

        val pfd = runCatching { resolver.openFileDescriptor(destinationUri, "rwt") }.getOrNull()
            ?: runCatching { resolver.openFileDescriptor(destinationUri, "wt") }.getOrNull()
            ?: runCatching { resolver.openFileDescriptor(destinationUri, "w") }.getOrNull()

        if (pfd != null) {
            pfd.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { fileOut ->
                    file.inputStream().use { fileIn ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        while (fileIn.read(buffer).also { read = it } != -1) {
                            fileOut.write(buffer, 0, read)
                            bytesWritten += read
                            onProgress(bytesWritten, fileLength)
                        }
                        fileOut.flush()
                        try {
                            descriptor.fileDescriptor.sync()
                        } catch (_: Throwable) {
                            // Some virtual file systems do not support sync, continue
                        }
                    }
                }
            }
        } else {
            val output = runCatching { resolver.openOutputStream(destinationUri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(destinationUri, "w")
                ?: error("The selected location could not be opened")
            output.use { rawOut ->
                file.inputStream().use { fileIn ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (fileIn.read(buffer).also { read = it } != -1) {
                        rawOut.write(buffer, 0, read)
                        bytesWritten += read
                        onProgress(bytesWritten, fileLength)
                    }
                    rawOut.flush()
                }
            }
        }

        check(bytesWritten == fileLength) {
            "Export incomplete: wrote ${formatFileSize(bytesWritten)} of ${formatFileSize(fileLength)}"
        }
        return bytesWritten
    }

    fun exportProjectZip(
        context: Context,
        workspaceRoot: File,
        slug: String,
        destinationUri: Uri,
        isExcluded: (String) -> Boolean,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long {
        val rootPath = workspaceRoot.canonicalFile.toPath()
        val resolver = context.contentResolver
        val output = resolver.openOutputStream(destinationUri)
            ?: error("The selected location could not be opened")

        val entriesToZip = mutableListOf<File>()
        workspaceRoot.walkTopDown()
            .onEnter { directory ->
                if (directory == workspaceRoot) {
                    true
                } else {
                    val relative = directory.relativeTo(workspaceRoot).invariantSeparatorsPath
                    !isExcluded(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                }
            }
            .drop(1)
            .filter { file ->
                !Files.isSymbolicLink(file.toPath()) &&
                    runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                    !isExcluded(file.relativeTo(workspaceRoot).invariantSeparatorsPath)
            }
            .forEach { entriesToZip.add(it) }

        val totalBytes = entriesToZip.filter { it.isFile }.sumOf { it.length() }
        var bytesWritten = 0L

        output.buffered().use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("$slug/"))
                zip.closeEntry()
                val buffer = ByteArray(64 * 1024)
                for (file in entriesToZip) {
                    val relative = file.relativeTo(workspaceRoot).invariantSeparatorsPath
                    val entryName = "$slug/$relative" + if (file.isDirectory) "/" else ""
                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                    if (file.isFile) {
                        file.inputStream().buffered().use { input ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                zip.write(buffer, 0, read)
                                bytesWritten += read
                                onProgress(bytesWritten, totalBytes)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return bytesWritten
    }

    fun shareSingleFile(context: Context, file: File) {
        require(file.isFile) { "Target is not a regular file: ${file.name}" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mimeType = mimeTypeForFileName(file.name)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share ${file.name}").apply {
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun openWithFile(context: Context, file: File) {
        require(file.isFile) { "Target is not a regular file: ${file.name}" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val config = determineOpenWithConfig(file.name)

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, config.mimeType)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (config.allowWrite) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val extraIntents = mutableListOf<Intent>()
        if (config.isApk) {
            extraIntents.add(
                Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } else {
            extraIntents.add(
                Intent(Intent.ACTION_EDIT).apply {
                    setDataAndType(uri, config.mimeType)
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        val chooserTitle = if (config.isApk) "Install or open ${file.name} with" else "Open ${file.name} with"
        val chooser = Intent.createChooser(viewIntent, chooserTitle).apply {
            clipData = ClipData.newRawUri(file.name, uri)
            if (extraIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (config.allowWrite) {
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun installWorkspaceApk(context: Context, file: File) {
        require(file.isFile && file.extension.equals("apk", ignoreCase = true) && file.length() > 0L) {
            "Not a valid APK: ${file.name}"
        }
        AndroidAppInstaller.install(context, file)
    }
}
