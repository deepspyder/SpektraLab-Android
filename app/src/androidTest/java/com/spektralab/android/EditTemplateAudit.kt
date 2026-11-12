package com.spektralab.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.*
import com.spektralab.android.data.local.EditBatchJournal
import com.spektralab.android.data.local.EditPresetStore
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.StockCatalog
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object EditTemplateAudit {
    fun models(context: Context): String {
        val (films, papers) = StockCatalog.load(context)
        val base = EditorParams()
        val source = EditorParams().apply {
            exposureEv = 1.5f; cameraWbCustom = true; cameraWbKelvin = 7200f
            grainAmount = 1.3f; printExposure = .8f; temperature = .25f; brightness = 18f
            vignetteAmount = 35f; cropX = .2f; cropW = .7f; quarterTurns = 1
            overscanActive = true; overscanCameraSeed = 734; overscanFrameSeed = 983; overscanFrameNumber = 23
            overscanFNumber = 2.8f; dateImprintText = "2024-07-03"; dateImprintDataText = "ISO 800"
            smartDevelopSummary = "source analysis"; smartDevelopStyle = "cinema"; smartDevelopVersion = 1
            toneCurves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .62f))
            colorWheels = ColorWheels(shadows = WheelAdjustment(220f, 25f, -8f))
            channelMixer = ChannelMixer(red = MixerRow(10f, -15f, 5f))
            colorAdjustments = ColorAdjustments.NEUTRAL.withVibrance(20f)
        }
        val target = EditorParams().apply {
            cropY = .15f; cropH = .8f; rotationDeg = 12f; flipH = true
            overscanCameraSeed = 111; overscanFrameSeed = 222; overscanFrameNumber = 31; overscanFNumber = 5.6f
            dateImprintText = "2025-10-06"; dateImprintDataText = "ISO 200"; smartDevelopSummary = "target analysis"
        }
        val known = base.toJson().keys().asSequence().toSet() + setOf("tone_curves", "color_adjustments", "channel_mixer", "color_wheels")
        val grouped = EditGroup.entries.flatMap { it.fields }
        check(grouped.size == grouped.distinct().size)
        check(grouped.toSet().intersect(EditGroup.LOCAL_FIELDS).isEmpty())
        check(grouped.toSet() + EditGroup.LOCAL_FIELDS == known) { "Unassigned serialized fields: ${known - grouped.toSet() - EditGroup.LOCAL_FIELDS}" }
        check(EditGroup.GEOMETRY !in EditGroup.DEFAULT && EditGroup.FRAME !in EditGroup.DEFAULT)
        var assertions = 0
        for (group in EditGroup.entries) {
            val template = EditTemplate.capture(source, setOf(group))
            val restored = EditTemplate.fromJson(template.toJson())
            val applied = restored.applyTo(target, true, films, papers).params ?: error("Rejected valid template")
            val old = target.toJson(); val after = applied.toJson(); val expected = source.toJson()
            for (key in known) {
                val reference = if (key in group.fields) expected else old
                // edge lettering is derived from material, not copied metadata.
                if (key != "overscan_edge_text") check(value(after, key) == value(reference, key)) { "$group changed $key" }
                assertions++
            }
            check(template.toJson().getJSONObject("values").keys().asSequence().none { it in EditGroup.LOCAL_FIELDS })
        }
        val template = EditTemplate.capture(source, EditGroup.DEFAULT)
        val png = template.applyTo(target, false, films, papers)
        check(EditTransferNotice.RAW_ONLY in png.notices)
        check(!png.params!!.cameraWbCustom && png.params!!.brightness == 18f && png.params!!.cropY == .15f)
        check(png.params!!.dateImprintText == target.dateImprintText && png.params!!.overscanFrameSeed == 222)
        val reset = EditTemplate.capture(base, setOf(EditGroup.CURVES, EditGroup.COLOR)).applyTo(source, true, films, papers).params!!
        check(reset.toneCurves.isIdentity && reset.channelMixer.isNeutral && reset.colorWheels.isNeutral && reset.colorAdjustments.isNeutral)
        check(runCatching { EditTemplate.capture(base, emptySet()) }.isFailure)
        for (bad in listOf(
            template.toJson().put("version", 99),
            template.toJson().put("groups", JSONArray().put("unknown")),
            template.toJson().apply { getJSONObject("values").put("uri", "content://must-not-copy") },
            template.toJson().apply { getJSONObject("values").put("brightness", "18") },
            template.toJson().apply { getJSONObject("values").remove("brightness") },
        )) check(runCatching { EditTemplate.fromJson(bad) }.isFailure)
        val future = target.copy().apply { unreadableColorWheels = "{\"version\":99,\"keep\":true}" }
        check(runCatching { EditTemplate.capture(future, setOf(EditGroup.COLOR)) }.isFailure)
        val light = EditTemplate.capture(source, setOf(EditGroup.LIGHT))
        check(light.applyTo(future, true, films, papers).params!!.unreadableColorWheels == future.unreadableColorWheels)
        val replacement = EditTemplate.capture(source, setOf(EditGroup.COLOR)).applyTo(future, true, films, papers)
        check(EditTransferNotice.COLOR_REPLACED in replacement.notices && replacement.params!!.unreadableColorWheels == null)
        val invalid = source.copy().apply { filmId = "does-not-exist" }
        check(EditTemplate.capture(invalid, setOf(EditGroup.MATERIAL)).applyTo(target, true, films, papers).problem == EditTransferProblem.UNKNOWN_FILM)
        val mono = films.first { it.monochrome }
        val mismatch = source.copy().apply { filmId = mono.id }
        check(light.applyTo(mismatch, true, films, papers).problem == EditTransferProblem.INCOMPATIBLE_MATERIAL)
        check(light.applyTo(target.copy().apply { paperId = "unknown-paper" }, true, films, papers).problem == EditTransferProblem.UNKNOWN_PAPER)
        val paired = EditTemplate.capture(mismatch, setOf(EditGroup.MATERIAL)).applyTo(target, true, films, papers)
        check(EditTransferNotice.PAPER_ADJUSTED in paired.notices && papers.first { it.id == paired.params!!.paperId }.monochrome)
        check(EditTemplate.capture(mismatch, setOf(EditGroup.MATERIAL)).applyTo(target, true, films, papers.filterNot { it.monochrome }).problem == EditTransferProblem.NO_COMPATIBLE_PAPER)
        return "10 group field ownership and $assertions isolation checks; defaults/geometry/local dates/seeds; RAW partial skip; material compatibility; neutral reset; version/types/unknown data and roundtrip passed"
    }

    fun storage(context: Context, output: File): String {
        val root = File(output, "edit-store-${System.nanoTime()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val recipe = File(root, "recipes.json").apply { writeText("export recipe remains independent") }
        val template = EditTemplate.capture(EditorParams().apply { brightness = 18f }, setOf(EditGroup.LIGHT))
        val initial = EditPresetStore.save(isolated, "  Evening  ", template)
        check(initial.presets.single().name == "Evening")
        val id = initial.presets.single().id
        check(EditPresetStore.load(isolated).presets.single().template.toJson().toString() == template.toJson().toString())
        check(runCatching { EditPresetStore.save(isolated, "evening", template) }.isFailure)
        check(runCatching { EditPresetStore.save(isolated, " ", template) }.isFailure)
        EditPresetStore.save(isolated, "Renamed", template, id)
        check(EditPresetStore.load(isolated).presets.single().id == id)
        val presetsFile = File(root, "edit-presets.json")
        val future = JSONObject().put("id", "future-id").put("name", "Later").put("template", JSONObject().put("version", 99))
        val json = JSONObject(presetsFile.readText()).apply { getJSONArray("presets").put(future) }
        presetsFile.writeText(json.toString())
        check(EditPresetStore.load(isolated).unsupported == 1)
        EditPresetStore.save(isolated, "Another", template)
        check(JSONObject(presetsFile.readText()).getJSONArray("presets").getJSONObject(1).toString() == future.toString())
        EditPresetStore.delete(isolated, id)
        check(EditPresetStore.load(isolated).presets.single().name == "Another")
        val futureRoot = JSONObject().put("version", 99).put("presets", JSONArray().put(future)).toString()
        presetsFile.writeText(futureRoot)
        check(runCatching { EditPresetStore.save(isolated, "Blocked", template) }.isFailure)
        check(presetsFile.readText() == futureRoot && recipe.readText() == "export recipe remains independent")

        // A terminated write after each durable point must roll forward exactly once.
        val keys = listOf("uri-1111111111111111", "uri-2222222222222222")
        val expected = keys.associateWith { JSONObject().put("marker", it).toString() }
        for (point in 0..2) {
            val directory = File(root, "journal-$point").apply { mkdirs() }
            check(runCatching { EditBatchJournal(directory) { if (it == point) throw IOException("simulated process stop") }.commit(expected) }.isFailure)
            check(File(directory, "pending-edit-batch.json").exists())
            EditBatchJournal(directory).recover()
            check(!File(directory, "pending-edit-batch.json").exists())
            keys.forEach { check(File(directory, "$it.json").readText() == expected[it]) }
            EditBatchJournal(directory).recover()
            keys.forEach { check(File(directory, "$it.json").readText() == expected[it]) }
        }
        val first = Uri.fromFile(File(root, "one.png")); val second = Uri.fromFile(File(root, "two.png"))
        EditStore.save(isolated, first, EditorParams())
        EditStore.save(isolated, second, EditorParams())
        val a = EditStore.loadForTransfer(isolated, first); val b = EditStore.loadForTransfer(isolated, second)
        EditStore.save(isolated, second, EditorParams().apply { contrast = 12f })
        val updated = EditorParams().apply { brightness = 18f }.toJson().toString()
        check(runCatching { EditStore.saveBatch(isolated, listOf(EditStore.BatchEdit(first, a.json, updated), EditStore.BatchEdit(second, b.json, updated))) }.isFailure)
        check(EditStore.load(isolated, first)!!.brightness == 0f)
        val sidecar = File(root, "sidecars").listFiles()!!.first { it.readText() == a.json }
        sidecar.writeText(JSONObject(a.json!!).put("future_extension", JSONObject().put("keep", true)).toString())
        val original = EditStore.loadForTransfer(isolated, first)
        EditStore.saveBatch(isolated, listOf(EditStore.BatchEdit(first, original.json, updated)))
        check(EditStore.load(isolated, first)!!.brightness == 18f)
        check(JSONObject(sidecar.readText()).getJSONObject("future_extension").getBoolean("keep"))
        val saved = EditStore.saveIfAbsent(isolated, first, EditorParams().apply { brightness = -30f })
        check(saved.brightness == 18f && EditStore.load(isolated, first)!!.brightness == 18f)
        EditStore.save(isolated, first, saved.copy().apply { contrast = 7f })
        check(JSONObject(sidecar.readText()).getJSONObject("future_extension").getBoolean("keep"))
        val targetWithFuture = EditorParams.fromJson(EditorParams().toJson().put("future_extension", JSONArray().put("target")))
        targetWithFuture.copySettingsFrom(saved)
        check(targetWithFuture.toJson().getJSONArray("future_extension").getString(0) == "target")
        return "Atomic preset CRUD/name rules and unsupported records/root preserved; independent export recipes; journal fault points 0/1/2 recover idempotently; batch CAS rejects all stale writes, unknown sidecar extensions kept, late import suggestion cannot overwrite manual edits"
    }

    private fun value(json: JSONObject, key: String): String = if (json.has(key)) json.get(key).toString() else "<absent>"
}
