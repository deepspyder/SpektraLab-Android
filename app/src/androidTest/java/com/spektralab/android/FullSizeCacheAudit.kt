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
internal class FullSizeCacheAudit(private val test: Instrumentation, private val output: File, private val withCurves: Boolean = false,
                                 private val withColor: Boolean = false, private val withMixer: Boolean = false, private val withWheels: Boolean = false,
                                 private val withPreset: Boolean = false, private val withMigration: Boolean = false) {
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
        var presetId: String? = null
        try {
            await { vm.engine != null && !vm.busy }
            EditStore.save(
                context,
                uri,
                EditorParams().apply {
                    grainActive = false
                    glareActive = false
                    if (withWheels || withMigration) colorWheels = com.spektralab.android.core.color.ColorWheels(
                        master = com.spektralab.android.core.color.WheelAdjustment(35f, 12f, 6f),
                        shadows = com.spektralab.android.core.color.WheelAdjustment(220f, 24f, -8f),
                        midtones = com.spektralab.android.core.color.WheelAdjustment(320f, 8f, 3f),
                        highlights = com.spektralab.android.core.color.WheelAdjustment(50f, 18f, 5f),
                    )
                    if (withMixer) channelMixer = com.spektralab.android.core.color.ChannelMixer(
                        red = com.spektralab.android.core.color.MixerRow(-15f, 12f, 3f),
                        blue = com.spektralab.android.core.color.MixerRow(7f, -3f, -4f),
                    )
                    if (withColor) colorAdjustments = com.spektralab.android.core.color.ColorAdjustments.NEUTRAL
                        .withVibrance(30f).with(com.spektralab.android.core.color.HslColor.BLUE,
                            com.spektralab.android.core.color.HslBand(25f, -15f, 12f))
                    if (withCurves) toneCurves = com.spektralab.android.core.color.ToneCurves.IDENTITY.with(
                        com.spektralab.android.core.color.CurveChannel.RGB,
                        com.spektralab.android.core.color.ToneCurve.IDENTITY.add(.5f,.63f))
                },
            )
            if (withMigration) {
                val sandbox = File(output, "raw-migration-${System.nanoTime()}").apply { mkdirs() }
                val isolated = object : android.content.ContextWrapper(context) {
                    override fun getFilesDir() = File(sandbox, "legacy-app").apply { mkdirs() }
                }
                val original = EditStore.load(context, uri)!!
                EditStore.save(isolated, uri, original)
                val key = EditStore.migrationKey(isolated, uri)
                val sourceEvidence = com.spektralab.android.data.library.SourceProbe.inspect(context, uri)
                check(sourceEvidence.verified)
                val archive = EditStore.captureMigrationCopy(isolated, File(sandbox, "archives"),
                    listOf(com.spektralab.android.core.model.LegacySourceBinding(key, sourceEvidence)), listOf(key), 0)
                com.spektralab.android.data.library.LibraryPrototypeRepository.openSandbox(File(sandbox, "db")).use { repo ->
                    check(repo.migrate(archive).single().state == com.spektralab.android.core.model.MigrationState.MIGRATED)
                    val migrated = repo.migratedVariant(key)!!
                    val restored = EditorParams.fromJson(org.json.JSONObject(migrated.paramsJson))
                    check(com.spektralab.android.core.model.EditSnapshot.capture(original) ==
                        com.spektralab.android.core.model.EditSnapshot.capture(restored))
                    // Only this known audit fixture is opened normally; shipping migration stays unconnected.
                    EditStore.save(context, uri, restored)
                }
            }
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            await { vm.errorMessage != null || vm.hasPhoto && !vm.busy }
            check(vm.errorMessage == null) { vm.errorMessage.orEmpty() }
            if (withPreset) {
                await {
                    vm.lastResult != null && vm.renderTaskState.active == null &&
                        listOf("renderJob", "wbJob").none { name ->
                            val field = EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                            (field.get(vm) as? kotlinx.coroutines.Job)?.isActive == true
                        }
                }
                val look = EditorParams().apply {
                    colorWheels = com.spektralab.android.core.color.ColorWheels(
                        master = com.spektralab.android.core.color.WheelAdjustment(35f, 12f, 6f),
                        shadows = com.spektralab.android.core.color.WheelAdjustment(220f, 24f, -8f),
                        midtones = com.spektralab.android.core.color.WheelAdjustment(320f, 8f, 3f),
                        highlights = com.spektralab.android.core.color.WheelAdjustment(50f, 18f, 5f),
                    )
                }
                val template = com.spektralab.android.core.model.EditTemplate.capture(look, setOf(com.spektralab.android.core.model.EditGroup.COLOR))
                val name = "RAW preset audit ${System.nanoTime()}"
                val preset = com.spektralab.android.data.local.EditPresetStore.save(context, name, template).presets.first { it.name == name }
                presetId = preset.id
                val loaded = com.spektralab.android.data.local.EditPresetStore.load(context).presets.first { it.id == preset.id }
                val preview = kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { vm.previewEditTransfer(loaded.template, listOf(uri)) }
                }
                check(preview.changes.size == 1)
                val native = vm.lastResult
                test.runOnMainSync { vm.applyEditTransfer(preview) }
                await { !vm.isApplyingEdits && !vm.busy && vm.params.colorWheels == look.colorWheels }
                check(vm.editTransferError == null && vm.lastResult === native)
            }
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
            if (withCurves || withColor || withMixer || withWheels || withPreset) check(first.hash != "3c7e8eee7848fa80cafd64faad1b3c2dd6d90b9de6ecee316d079f0da85fc8d3") { "Active post edit did not affect full-size RAW export" }
            if (withPreset || withMigration) check(first.hash == "62f1d58c709a4bc694ca93f8d552954320f921260fc64dd4c013bf4461926f3f") { "Preset/migration changed the existing wheel TIFF output" }
            check(vm.exportCacheStats.hits == afterFirst.hits + 1)
            check(vm.exportCacheStats.writes == afterFirst.writes)
            return "6064x4040 RAW TIFF byte-exact; bytes=${first.bytes}; sha256=${first.hash}; first_ms=${first.ms}; second_ms=${second.ms}; initial=$stats first=$afterFirst final=${vm.exportCacheStats}"
        } finally {
            presetId?.let { com.spektralab.android.data.local.EditPresetStore.delete(context, it) }
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
