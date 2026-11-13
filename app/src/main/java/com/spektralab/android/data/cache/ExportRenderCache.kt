package com.spektralab.android.data.cache

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.ExportOptions
import com.spektralab.android.core.model.RenderCacheIdentity
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.data.local.EditStore
import java.io.File
import java.security.MessageDigest

/**
 * Cache only proven deterministic requests. Default stochastic effects always keep the old path.
 */
internal class ExportRenderCache private constructor(private val context: Context) {
    data class Request(val key: String, val sourceHash: String)

    data class Stats(
        val hits: Long = 0,
        val misses: Long = 0,
        val writes: Long = 0,
        val bypasses: Long = 0,
    )

    private val directory = File(context.cacheDir, "export-rgba16-v1")
    private val disk by lazy { RenderDiskCache(directory, MAX_BYTES) }
    @Volatile private var resourceHash: String? = null
    @Volatile
    var stats = Stats()
        private set

    private fun updateStats(change: (Stats) -> Stats) = synchronized(this) { stats = change(stats) }

    fun prepare(
        uri: Uri,
        params: EditorParams,
        options: ExportOptions,
        cancel: () -> Unit,
    ): Request? {
        // No public explicit stochastic render seed in this engine. Do not change its RNG contract.
        // Date backs with auto text and overscan exposure also remain outside this first admission
        // scope.
        if (
            params.grainActive ||
                params.glareActive ||
                params.dateImprintActive ||
                params.overscanActive
        ) {
            updateStats { it.copy(bypasses = it.bypasses + 1) }
            return null
        }
        val sourceUri = safe { EditStore.editSourceUri(context, uri) }
        if (sourceUri == null || sourceUri.scheme != "file" && sourceUri.authority != "media") {
            updateStats { it.copy(bypasses = it.bypasses + 1) }
            return null // do not start an extra cloud/pipe fetch just to probe cache eligibility
        }
        return safe {
            val source = fingerprint(uri, cancel) ?: return@safe null
            val resources =
                resourceHash
                    ?: resourcesFingerprint(cancel)?.also { resourceHash = it }
                    ?: return@safe null
            val build = EngineClient.buildInfo()
            val key =
                RenderCacheIdentity(
                        source,
                        "libraw-0.22.2+android-decode-1",
                        "$build|${buildIdentity()}",
                        resources,
                        0,
                        0,
                        "decoded-by-source-v1",
                        options.outputColorSpace,
                        options.correctLens,
                        0L,
                        EditSnapshot.capture(params),
                        options.resolution,
                    )
                    .key
            Request(key, source)
        }
    }

    fun acquire(request: Request, cancel: () -> Unit): RenderDiskCache.Lease? {
        cancel()
        val lease = safe { disk.acquire(request.key, cancel) }
        try {
            cancel()
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
        updateStats {
            if (lease == null) it.copy(misses = it.misses + 1) else it.copy(hits = it.hits + 1)
        }
        return lease
    }

    fun store(request: Request, uri: Uri, result: Rgba16Source, cancel: () -> Unit) {
        val size = result.width.toLong() * result.height * 8 + 68
        // Published entries plus a temporary replacement must leave a separate reserve for the real
        // output.
        if (result.width > 131072 || size > MAX_BYTES) return
        safe {
            if (context.cacheDir.usableSpace < size * 2 + RESERVE_BYTES) return@safe false
            if (fingerprint(uri, cancel) != request.sourceHash) return@safe false
            disk.put(request.key, result, cancel).also { written ->
                if (written) updateStats { it.copy(writes = it.writes + 1) }
            }
        }
        cancel()
    }

    /** Re-read content for every request; a URI/size/mtime cache cannot prove source identity. */
    private fun fingerprint(reference: Uri, cancel: () -> Unit): String? {
        val uri = EditStore.editSourceUri(context, reference)
        if (uri.scheme == "file") {
            val source = File(uri.path ?: return null)
            if (!source.isFile) return null
            return source.inputStream().use { hash(it, cancel) }
        }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (
                !OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode) ||
                    Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_CUR) != 0L
            )
                return null
            // Read the provider's granted descriptor; reopening its /proc path can lose
            // MediaStore FUSE access/redaction semantics even when the descriptor is seekable.
            return ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                )
                .use { hash(it, cancel) }
        }
        return null
    }

    private fun resourcesFingerprint(cancel: () -> Unit): String? {
        // Hash actual files handed to the native engine, including any stale or extra resource.
        // Lens corrections read their own asset; include it too.
        val root = File(context.filesDir, "engine/resources")
        if (!root.isDirectory) return null
        val files =
            root
                .walkTopDown()
                .filter(File::isFile)
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        if (files.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            cancel()
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            file.inputStream().use {
                digest.update(hash(it, cancel).toByteArray(Charsets.US_ASCII))
            }
        }
        context.assets.open("engine/lens_profiles.json").use {
            digest.update(hash(it, cancel).toByteArray(Charsets.US_ASCII))
        }
        // The code is part of cache identity even for same-version local builds.
        // APKs are immutable while this application process owns its engine.
        File(context.applicationInfo.sourceDir).inputStream().use {
            digest.update(hash(it, cancel).toByteArray(Charsets.US_ASCII))
        }
        return hex(digest.digest())
    }

    private fun buildIdentity(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            "${it.lastUpdateTime}:${it.longVersionCodeCompat()}"
        }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private fun hash(input: java.io.InputStream, cancel: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            cancel()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        cancel()
        return hex(digest.digest())
    }

    // Cache errors never mask output/decoding errors. Cancellation must propagate even through I/O
    // failure.
    private inline fun <T> safe(action: () -> T): T? =
        try {
            action()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            android.util.Log.w("SpkCache", "Cache I/O unavailable")
            null
        } catch (error: android.system.ErrnoException) {
            null
        } catch (error: SecurityException) {
            null
        } catch (error: IllegalStateException) {
            android.util.Log.w("SpkCache", "Cache state unavailable")
            null
        } catch (error: OutOfMemoryError) {
            null
        }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val MAX_BYTES = 512L * 1024 * 1024
        private const val RESERVE_BYTES = 256L * 1024 * 1024
        @Volatile private var instance: ExportRenderCache? = null

        fun get(context: Context): ExportRenderCache =
            instance
                ?: synchronized(this) {
                    instance ?: ExportRenderCache(context.applicationContext).also { instance = it }
                }
    }
}
