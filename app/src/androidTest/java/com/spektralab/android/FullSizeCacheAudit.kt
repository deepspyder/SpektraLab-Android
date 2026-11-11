package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import java.security.MessageDigest

/** Stream checksums rather than bringing a 147 MB TIFF into the small ART heap. */
internal class FullSizeCacheAudit(private val test: Instrumentation, private val output: File, private val withCurves: Boolean = false, private val withColor: Boolean = false) {
    fun run(): String {
        val source = File(output, "perf-z6ii.NEF")
        check(source.isFile) { "Push perf-z6ii.NEF before running this explicit camera-file audit" }
        val context = test.targetContext
        val activity =
            test.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        fun media(): Set<Long> = buildSet {
            resolver
                .query(collection, arrayOf(MediaStore.Images.Media._ID), null, null, null)
                ?.use { while (it.moveToNext()) add(it.getLong(0)) }
        }
        val before = media()
        val uri = Uri.fromFile(source)
        val previous = EditStore.load(context, uri)
        try {
            await { vm.engine != null && !vm.busy }
            EditStore.save(
                context,
                uri,
                EditorParams().apply {
                    grainActive = false
                    glareActive = false
                    if (withColor) colorAdjustments = com.spektralab.android.core.color.ColorAdjustments.NEUTRAL
                        .withVibrance(30f).with(com.spektralab.android.core.color.HslColor.BLUE,
                            com.spektralab.android.core.color.HslBand(25f, -15f, 12f))
                    if (withCurves) toneCurves = com.spektralab.android.core.color.ToneCurves.IDENTITY.with(
                        com.spektralab.android.core.color.CurveChannel.RGB,
                        com.spektralab.android.core.color.ToneCurve.IDENTITY.add(.5f,.63f))
                },
            )
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.errorMessage != null || vm.hasPhoto && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            test.runOnMainSync {
                vm.exportFormat = "TIFF"
                vm.exportColorSpace = "sRGB"
                vm.exportRes = 0
                vm.exportKeepCapture = false
                vm.exportKeepLocation = false
            }
            data class Saved(val hash: String, val bytes: Long, val ms: Long)
            fun export(): Saved {
                val seen = media()
                val start = SystemClock.elapsedRealtime()
                test.runOnMainSync { vm.export() }
                await { !vm.isExporting && vm.renderTaskState.active == null }
                val elapsed = SystemClock.elapsedRealtime() - start
                check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
                val added = media() - seen
                check(added.size == 1)
                val result = android.content.ContentUris.withAppendedId(collection, added.single())
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                resolver.openInputStream(result)!!.use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        bytes += count
                    }
                }
                check(vm.log.contains("导出成功: 6064×4040")) { vm.log.takeLast(500) }
                return Saved(
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                    bytes,
                    elapsed,
                )
            }
            val stats = vm.exportCacheStats
            val first = export()
            val afterFirst = vm.exportCacheStats
            val second = export()
            check(first.hash == second.hash && first.bytes == second.bytes)
            if (withCurves || withColor) check(first.hash != "3c7e8eee7848fa80cafd64faad1b3c2dd6d90b9de6ecee316d079f0da85fc8d3") { "Active curves did not affect full-size RAW export" }
            check(vm.exportCacheStats.hits == afterFirst.hits + 1)
            check(vm.exportCacheStats.writes == afterFirst.writes)
            return "6064x4040 RAW TIFF byte-exact; bytes=${first.bytes}; sha256=${first.hash}; first_ms=${first.ms}; second_ms=${second.ms}; initial=$stats first=$afterFirst final=${vm.exportCacheStats}"
        } finally {
            if (previous != null) EditStore.save(context, uri, previous)
            (media() - before).forEach {
                resolver.delete(
                    android.content.ContentUris.withAppendedId(collection, it),
                    null,
                    null,
                )
            }
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 180000) {
                "Full-size cache audit timed out"
            }
            SystemClock.sleep(20)
        }
    }
}
