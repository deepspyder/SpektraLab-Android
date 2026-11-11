package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.core.model.RenderCacheIdentity
import com.spektralab.android.data.cache.RenderDiskCache
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.EngineTaskQueue
import com.spektralab.android.engine.Rgba16Source
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Real save-path races, immutable values, and session ownership across suspension. */
internal class RenderFoundationAudit(private val test: Instrumentation) {
    fun run(): String {
        snapshots()
        queue()
        diskCache()
        frozenExports()
        return "Immutable/canonical edits; serial transactions across suspension; cancelled waiters; exact/bounded/leased/corruption-safe disk storage; frozen single/batch export options and per-photo edits"
    }

    private fun snapshots() {
        val edit =
            EditorParams().apply {
                contrast = 17f
                cropX = .1f
                cropW = .8f
            }
        val snapshot = EditSnapshot.capture(edit)
        val hash = snapshot.fingerprint
        edit.contrast = -42f
        check(snapshot.toParams().contrast == 17f)
        snapshot.toParams().apply { contrast = 99f }
        check(snapshot.toParams().contrast == 17f && snapshot.fingerprint == hash)
        check(EditSnapshot.capture(snapshot.toParams()) == snapshot)
        val left =
            JSONObject()
                .put("b", JSONArray().put(2).put(1))
                .put("a", JSONObject().put("z", -0.0).put("x", "line\nquoted\""))
        val right =
            JSONObject()
                .put("a", JSONObject().put("x", "line\nquoted\"").put("z", 0))
                .put("b", JSONArray().put(2).put(1))
        check(EditSnapshot.fromJson(left) == EditSnapshot.fromJson(right))
        right.put("b", JSONArray().put(1).put(2))
        check(EditSnapshot.fromJson(left) != EditSnapshot.fromJson(right))
        val identity =
            RenderCacheIdentity(
                "a".repeat(64),
                "decoder-1",
                "engine-1",
                "b".repeat(64),
                257,
                39,
                "ProPhoto RGB",
                "sRGB",
                true,
                7L,
                snapshot,
            )
        check(identity.key == identity.copy().key)
        val changed =
            listOf(
                identity.copy(sourceFingerprint = "c".repeat(64)),
                identity.copy(resourcesFingerprint = "c".repeat(64)),
                identity.copy(decoderVersion = "decoder-2"),
                identity.copy(engineVersion = "engine-2"),
                identity.copy(width = 258),
                identity.copy(height = 40),
                identity.copy(inputColorSpace = "sRGB"),
                identity.copy(outputColorSpace = "Display P3"),
                identity.copy(correctLens = false),
                identity.copy(renderSeed = 8L),
                identity.copy(edit = EditSnapshot.capture(edit)),
            )
        check(
            changed.all { it.key != identity.key } &&
                changed.map { it.key }.toSet().size == changed.size
        )
    }

    private fun queue() = runBlocking {
        withTimeout(5000) {
            val queue = EngineTaskQueue()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val first = async {
                queue.onEngine("first") {
                    events += "first-start"
                    entered.complete(Unit)
                    resume.await() // the dispatcher is free, but this session is still owned
                    events += "first-end"
                }
            }
            entered.await()
            val cancelled = async { queue.onEngine("cancelled") { error("Cancelled waiter ran") } }
            delay(20)
            cancelled.cancelAndJoin()
            val second = async { queue.onEngine("second") { events += "second" } }
            delay(20)
            check(events == listOf("first-start") && queue.active?.kind == "first")
            resume.complete(Unit)
            first.await()
            second.await()
            check(events == listOf("first-start", "first-end", "second"))
            check(queue.active == null)
            runCatching { queue.run("failure") { error("sentinel") } }
            check(queue.active == null)
            queue.run("recovered") { check(queue.active?.kind == "recovered") }
        }
    }

    private fun diskCache() {
        val directory = File(test.targetContext.cacheDir, "render-cache-audit-${System.nanoTime()}")
        val source =
            object : Rgba16Source {
                override val width = 257
                override val height = 39

                override fun readRows(y: Int, rows: Int, destination: ShortArray) {
                    for (i in 0 until width * rows * 4) destination[i] =
                        ((y * width * 4 + i) * 17).toShort()
                }
            }
        val keyA = "a".repeat(64)
        val keyB = "b".repeat(64)
        val budget = 68L + source.width * source.height * 8
        try {
            val cache = RenderDiskCache(directory, budget)
            check(cache.put(keyA, source))
            val lease = cache.acquire(keyA) ?: error("Cache miss after completed write")
            val actual = ShortArray(source.width * 35 * 4)
            val expected = ShortArray(actual.size)
            lease.readRows(3, 35, actual)
            source.readRows(3, 35, expected)
            check(actual.contentEquals(expected)) { "A cached sixteen-bit sample changed" }
            val directTiff =
                ByteArrayOutputStream()
                    .also { TiffWriter.writeNative(source, null, null, it) }
                    .toByteArray()
            val cachedTiff =
                ByteArrayOutputStream()
                    .also { TiffWriter.writeNative(lease, null, null, it) }
                    .toByteArray()
            check(directTiff.contentEquals(cachedTiff)) {
                "File-backed TIFF differs from its original rows"
            }
            check(!cache.put(keyA, source)) { "A leased entry was replaced" }
            check(!cache.put(keyB, source)) { "A leased entry was evicted to admit another" }
            lease.close()
            lease.close()
            check(runCatching { lease.readRows(0, 1, actual) }.isFailure)
            check(cache.put(keyB, source))
            check(cache.acquire(keyA) == null)
            check(directory.listFiles()!!.sumOf(File::length) <= budget)
            val failed =
                runCatching { cache.put(keyB, source) { error("cancel sentinel") } }
                    .exceptionOrNull()
            check(failed?.message == "cancel sentinel")
            check(directory.listFiles()!!.none { it.name.endsWith(".partial") })
            RenderDiskCache(directory, budget).acquire(keyB)?.use {
                it.readRows(3, 35, actual)
                check(actual.contentEquals(expected))
            } ?: error("Cache did not survive reopening")
            RandomAccessFile(File(directory, "$keyB.rgba16"), "rw").use {
                it.seek(70)
                it.writeByte(123)
            }
            check(cache.acquire(keyB) == null) { "Corrupt pixels were accepted" }
            check(!File(directory, "$keyB.rgba16").exists())
            check(runCatching { cache.acquire("../escape") }.isFailure)
            check(!RenderDiskCache(directory, 68).put(keyA, source))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun frozenExports() {
        val activity =
            test.startActivitySync(
                Intent(test.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val resolver = test.targetContext.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun media(): Set<Long> = buildSet {
            resolver
                .query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { while (it.moveToNext()) add(it.getLong(0)) }
        }
        val before = media()
        val files = (0..1).map { File(test.targetContext.cacheDir, "foundation-source-$it.jpg") }
        try {
            await { vm.engine != null && !vm.busy }
            val bitmap = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff896747.toInt())
                files.forEach { file ->
                    file.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
                    }
                }
            } finally {
                bitmap.recycle()
            }
            val uris = files.map(Uri::fromFile)
            val edit =
                EditorParams().apply {
                    grainActive = false
                    glareActive = false
                }
            uris.forEach { EditStore.save(test.targetContext, it, edit) }
            test.runOnMainSync { vm.openPhotos(uris) }
            await { vm.errorMessage != null || vm.hasPhoto && !vm.busy && vm.lastResult != null }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            test.runOnMainSync {
                vm.exportFormat = "TIFF"
                vm.exportColorSpace = "sRGB"
                vm.exportRes = 0
                vm.exportKeepCapture = false
                vm.exportKeepLocation = false
                vm.export()
                // A queued task must use the choice it was submitted with, even if UI state
                // changes.
                vm.exportFormat = "PNG"
                vm.exportColorSpace = "Display P3"
                vm.exportKeepCapture = true
            }
            await { !vm.isExporting }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val single = media() - before
            check(single.size == 1)
            val singleUri = android.content.ContentUris.withAppendedId(collection, single.single())
            check(resolver.getType(singleUri) == "image/tiff") {
                "Queued export read a later format instead of the frozen TIFF recipe"
            }

            test.runOnMainSync {
                vm.exportFormat = "TIFF"
                vm.exportColorSpace = "sRGB"
                vm.exportKeepCapture = false
                vm.batchExport()
                // The second photo's sidecar can change while the first output is being written.
                EditStore.save(test.targetContext, uris[1], edit.copy().apply { contrast = 80f })
                vm.exportFormat = "JPEG"
            }
            await { !vm.isBatchExporting }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            val batch = media() - before - single
            check(batch.size == 2)
            val bytes =
                batch.map {
                    val uri = android.content.ContentUris.withAppendedId(collection, it)
                    check(resolver.getType(uri) == "image/tiff")
                    resolver.openInputStream(uri)!!.use { stream -> stream.readBytes() }
                }
            check(bytes[0].contentEquals(bytes[1])) {
                "Batch read a changed sidecar after submission"
            }
        } finally {
            (media() - before).forEach {
                resolver.delete(
                    android.content.ContentUris.withAppendedId(collection, it),
                    null,
                    null,
                )
            }
            files.forEach(File::delete)
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun await(timeout: Long = 60000, condition: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - started < timeout) {
                "Render foundation audit timed out"
            }
            SystemClock.sleep(20)
        }
    }
}
