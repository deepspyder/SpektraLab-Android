package com.spektralab.android

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import com.spektralab.android.core.color.*
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.*
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.data.export.TiffWriter
import com.spektralab.android.engine.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** Isolated directories and provider fixtures only: never moves or migrates real app records. */
internal class LibraryMigrationAudit(private val test: Instrumentation, private val output: File) {
    private val context = test.targetContext
    private val root = File(output, "migration-${System.nanoTime()}").apply { mkdirs() }
    private val legacy = File(root, "legacy").apply { mkdirs() }
    private val snapshots = File(root, "archives")
    private val fixture = File(root, "image.png").also { picture(it) }
    private val evidence by lazy { SourceProbe.inspect(context, Uri.fromFile(fixture)) }
    private val key = "uri-1111111111111111"
    private val secondKey = "uri-2222222222222222"

    fun identityAndCapture(): String {
        val source = evidence
        check(source.verified && source.width == 96 && source.height == 64 && source.orientation == 1)
        val copy = File(root, "same-copy.png").also { fixture.copyTo(it) }
        val copied = SourceProbe.inspect(context, Uri.fromFile(copy))
        check(copied.sha256 == source.sha256 && copied.sourceKey != source.sourceKey)
        context.contentResolver.call(Uri.parse("content://com.spektralab.android.test.migration-a"), "seed", null,
            android.os.Bundle().apply { putByteArray("png", fixture.readBytes()) })
        val providerUris = listOf("content://com.spektralab.android.test.migration-a/volume-a/42",
            "content://com.spektralab.android.test.migration-b/volume-b/42", "content://com.spektralab.android.test.migration-a/pipe/42")
        val sources = providerUris.map { SourceProbe.inspect(context, Uri.parse(it)) }
        check(sources.all { it.verified && it.mediaId == 42L && it.sha256 == source.sha256 && it.width == 96 && it.height == 64 })
        check(sources.map { it.sourceKey }.distinct().size == 3)
        check(SourceProbe.inspect(context, Uri.parse("content://com.spektralab.android.test.migration-a/denied/42")).availability == SourceAvailability.PERMISSION_DENIED)
        check(SourceProbe.inspect(context, Uri.parse("content://com.spektralab.android.test.migration-a/missing/42")).availability == SourceAvailability.MISSING)
        check(runCatching { SourceProbe.inspect(context, Uri.fromFile(fixture)) { throw kotlinx.coroutines.CancellationException("cancel") } }.isFailure)
        val raw = "{\n \"brightness\": 17, \"crop_x\":0.1, \"future_extra\":{\"x\":[1,true]}\n}"
        File(legacy, "$key.json").writeText("partial newer write")
        File(legacy, "$key.json.bak").writeText(raw)
        val before = directoryHashes(legacy)
        val archive = LegacyMigrationArchive.capture(legacy, snapshots, listOf(LegacySourceBinding(key, source)), listOf(key), 0)
        check(archive.payload(archive.entries.single()) == raw)
        check(directoryHashes(legacy) == before)
        check(runCatching { LegacyMigrationArchive.capture(legacy, File(legacy, "nested"), emptyList(), emptyList(), null) }.isFailure)
        File(legacy, "pending-edit-batch.json").writeText("pending")
        check(runCatching { LegacyMigrationArchive.capture(legacy, snapshots, emptyList(), emptyList(), null) }.isFailure)
        // Do not remove the simulated pending file. A separate isolated case validates normal migration.
        val bad = File(archive.directory, archive.entries.single().relativePath)
        bad.appendText("tampered")
        LibraryPrototypeRepository.openSandbox(File(root, "tampered-db")).use { repo ->
            check(runCatching { repo.migrate(archive) }.isFailure)
            check(repo.counts().assets == 0L)
        }
        return "File/content/pipe fingerprint and bounds; provider/volume identity distinct despite same id42/name/hash; denied/missing/cancelled probes; immutable backup-first capture preserves .bak/live files, refuses pending batches and nested destinations, tampered archive writes no assets"
    }

    fun migrationAndOwnership(): String {
        val edited = EditorParams().apply {
            brightness = 17f; cropX = .1f; cropW = .8f; rotationDeg = 8f
            dateImprintText = "2025-06-03"; overscanFrameSeed = 742
            colorWheels = ColorWheels(shadows = WheelAdjustment(220f, 25f, -8f))
            toneCurves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .63f))
        }
        val raw = edited.toJson().toString(2)
        File(legacy, "$key.json").writeText(raw)
        File(legacy, "$secondKey.json").writeText("{\"brightness\":-4}")
        val archive = LegacyMigrationArchive.capture(legacy, snapshots, listOf(LegacySourceBinding(key, evidence)),
            listOf(key, secondKey, key), 1)
        val before = directoryHashes(legacy)
        val database = File(root, "identity-db")
        lateinit var variantId: String
        LibraryPrototypeRepository.openSandbox(database).use { repo ->
            val records = repo.migrate(archive)
            check(records.first { it.legacyKey == key }.state == MigrationState.MIGRATED)
            check(records.first { it.legacyKey == secondKey }.state == MigrationState.PENDING_SOURCE)
            val original = repo.migratedVariant(key)!!
            variantId = original.id
            check(original.support == LibraryEditSupport.LEGACY && original.editSchemaVersion == 1 &&
                original.renderVersion == LibraryPrototypeRepository.LEGACY_RENDER)
            check(original.paramsJson == CanonicalJson.encode(JSONObject(raw)))
            check(EditSnapshot.capture(EditorParams.fromJson(JSONObject(original.paramsJson))) == EditSnapshot.capture(edited))
            val session = repo.session(archive.id)!!
            check(session.currentPosition == 1 && session.slots.map { it.first } == listOf(key, secondKey, key))
            check(session.slots[1].second == null && session.slots[0].second == session.slots[2].second)
            repeat(3) { repo.migrate(LegacyMigrationArchive.open(archive.directory)) }
            check(repo.counts().assets == 1L && repo.counts().variants == 1L)
            val gateway = MigrationSandboxGateway(repo, archive)
            val changed = JSONObject(original.paramsJson).put("brightness", 29)
            val saved = gateway.save(key, original.revision, changed.toString())
            check(saved.revision == 2L && JSONObject(gateway.read(key)!!).getInt("brightness") == 29)
            check(gateway.read(secondKey) == "{\"brightness\":-4}")
            check(runCatching { gateway.save(secondKey, 1, "{}") }.isFailure)
            check(runCatching { gateway.save(key, 1, "{}") }.isFailure)
            repo.migrate(archive)
            check(JSONObject(repo.variant(variantId)!!.paramsJson).getInt("brightness") == 29)
            val virtual = repo.virtualCopy(variantId, 2)
            check(!virtual.isDefault && virtual.assetId == saved.assetId && virtual.paramsJson == saved.paramsJson)
            repo.saveVariant(virtual.id, 1, JSONObject(virtual.paramsJson).put("brightness", -12).toString())
            check(JSONObject(repo.variant(variantId)!!.paramsJson).getInt("brightness") == 29)
            check(repo.counts().assets == 1L && repo.counts().variants == 2L && fixture.length() == evidence.size)
            check(directoryHashes(legacy) == before)
        }
        LibraryPrototypeRepository.openSandbox(database).use { reopened ->
            check(reopened.variant(variantId)!!.revision == 2L)
            check(JSONObject(reopened.variant(variantId)!!.paramsJson).getInt("brightness") == 29)
            check(reopened.session(archive.id)!!.slots.size == 3)
        }
        val actualContext = object : ContextWrapper(context) { override fun getFilesDir() = File(root, "old-app").apply { mkdirs() } }
        val uri = Uri.fromFile(fixture)
        EditStore.save(actualContext, uri, edited)
        val actualKey = EditStore.migrationKey(actualContext, uri)
        val copied = EditStore.captureMigrationCopy(actualContext, File(root, "adapter-archives"),
            listOf(LegacySourceBinding(actualKey, evidence)), listOf(actualKey), 0)
        check(copied.entries.single().key == actualKey)
        check(EditSnapshot.capture(EditorParams.fromJson(JSONObject(copied.payload(copied.entries.single())))) == EditSnapshot.capture(edited))
        return "Legacy parameters/crop/date/seed exact; default asset/variant/session with unresolved slot and repeated placements; rerun/reopen identity stable; CAS writer and virtual-copy independence; legacy/archive bytes unchanged; EditStore sandbox adapter captures real key contract"
    }

    fun interruptedTransactions(): String {
        File(legacy, "$key.json").writeText("{\"brightness\":12}")
        File(legacy, "$secondKey.json").writeText("{\"brightness\":-8}")
        val other = File(root, "other.png").also { fixture.copyTo(it) }
        val archive = LegacyMigrationArchive.capture(legacy, snapshots,
            listOf(LegacySourceBinding(key, evidence), LegacySourceBinding(secondKey, SourceProbe.inspect(context, Uri.fromFile(other)))),
            listOf(key, secondKey), 1)
        var faultCount = 0
        for (point in LibraryPrototypeRepository.Checkpoint.entries) {
            val database = File(root, "fault-${point.name}")
            LibraryPrototypeRepository.openSandbox(database).use { repo ->
                check(runCatching { repo.migrate(archive) { at, _ ->
                    if (at == point) throw IOException("simulated full disk/process interruption")
                } }.isFailure)
                when (point) {
                    LibraryPrototypeRepository.Checkpoint.BEFORE_RECORD, LibraryPrototypeRepository.Checkpoint.RECORD_WRITTEN ->
                        check(repo.counts().assets == 0L && repo.counts().variants == 0L && repo.counts().migrations == 0L)
                    LibraryPrototypeRepository.Checkpoint.RECORD_COMMITTED -> check(repo.counts().assets == 1L && repo.counts().migrations == 1L)
                    LibraryPrototypeRepository.Checkpoint.SESSION_WRITTEN -> check(repo.counts().assets == 2L && repo.counts().sessions == 0L)
                    LibraryPrototypeRepository.Checkpoint.SESSION_COMMITTED -> check(repo.counts().sessions == 1L)
                }
            }
            LibraryPrototypeRepository.openSandbox(database).use { repo ->
                repeat(2) { check(repo.migrate(archive).all { it.state == MigrationState.MIGRATED }) }
                check(repo.counts() == LibraryPrototypeRepository.Counts(2, 2, 2, 2, 1))
                check(repo.session(archive.id)!!.currentPosition == 1)
            }
            faultCount++
        }
        // Real SQLite version refusal must preserve the future database, not recreate it.
        val future = File(root, "future-db").apply { mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(File(future, "library-prototype.db"), null).use { db ->
            db.execSQL("CREATE TABLE future_marker(value TEXT)")
            db.execSQL("INSERT INTO future_marker VALUES('keep')")
            db.version = 99
        }
        check(runCatching { LibraryPrototypeRepository.openSandbox(future) }.isFailure)
        SQLiteDatabase.openDatabase(File(future, "library-prototype.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 99)
            db.rawQuery("SELECT value FROM future_marker", null).use { check(it.moveToFirst() && it.getString(0) == "keep") }
        }
        return "$faultCount durable/transaction checkpoints: abort before/during/after row and session commits, close/reopen/resume twice without duplicates; real SQLite schema99 refused without reset"
    }

    fun unresolvedAndVersions(): String {
        val malformedKey = "uri-3333333333333333"
        val futureKey = "uri-4444444444444444"
        val deniedKey = "uri-5555555555555555"
        val largeKey = "uri-6666666666666666"
        File(legacy, "$key.json").writeText("{}")
        File(legacy, "$secondKey.json").writeText("{}")
        File(legacy, "$malformedKey.json").writeText("{not-json")
        val futureRaw = "{\"schema_version\":99,\"brightness\":13,\"color_wheels\":{\"version\":99,\"keep\":[true,7]}}"
        File(legacy, "$futureKey.json").writeText(futureRaw)
        File(legacy, "$deniedKey.json").writeText("{}")
        File(legacy, "$largeKey.json").writeText(" ".repeat(LegacyMigrationArchive.MAX_EDIT_BYTES.toInt()+1))
        val second = File(root, "second.png").also { fixture.copyTo(it) }
        val probe = SourceProbe.inspect(context, Uri.fromFile(second))
        val futureSource = probe.copy(sourceKey = CanonicalJson.fingerprint("future"), uri = "content://audit/future")
        val denied = evidence.copy(availability = SourceAvailability.PERMISSION_DENIED, sha256 = null)
        val bindings = listOf(LegacySourceBinding(key, evidence), LegacySourceBinding(key, probe),
            LegacySourceBinding(malformedKey, evidence), LegacySourceBinding(futureKey, futureSource), LegacySourceBinding(deniedKey, denied))
        val archive = LegacyMigrationArchive.capture(legacy, snapshots, bindings, listOf(secondKey, deniedKey, futureKey), 1)
        val database = File(root, "unresolved-db")
        LibraryPrototypeRepository.openSandbox(database).use { repo ->
            val outcomes = repo.migrate(archive).associateBy { it.legacyKey }
            check(outcomes.getValue(key).state == MigrationState.AMBIGUOUS)
            check(outcomes.getValue(secondKey).state == MigrationState.PENDING_SOURCE)
            check(outcomes.getValue(deniedKey).state == MigrationState.PENDING_SOURCE)
            check(outcomes.getValue(malformedKey).state == MigrationState.INVALID)
            check(outcomes.getValue(largeKey).state == MigrationState.INVALID)
            val future = repo.migratedVariant(futureKey)!!
            check(future.support == LibraryEditSupport.PRESERVED_READ_ONLY && future.renderVersion == "preserved-unknown")
            check(future.paramsJson == CanonicalJson.encode(JSONObject(futureRaw)))
            check(runCatching { repo.saveVariant(future.id, 1, "{}") }.isFailure)
            check(repo.virtualCopy(future.id, 1).support == LibraryEditSupport.PRESERVED_READ_ONLY)
            val rebound = LegacyMigrationArchive.capture(legacy, snapshots,
                listOf(LegacySourceBinding(key, evidence), LegacySourceBinding(secondKey, probe)), listOf(secondKey, key), 0)
            repo.migrate(rebound)
            check(repo.migratedVariant(key) != null && repo.migratedVariant(secondKey) != null)
            check(repo.session(archive.id)!!.slots[0].second == repo.migratedVariant(secondKey)!!.id)
            check(repo.session(archive.id)!!.currentPosition == 1 && repo.session(archive.id)!!.slots[1].second == null)
            check(repo.migratedVariant(key)!!.assetId != repo.migratedVariant(secondKey)!!.assetId) { "Equal bytes merged distinct sources" }
            val current = repo.migratedVariant(key)!!
            File(legacy, "$key.json").writeText("{\"brightness\":90}")
            val changed = LegacyMigrationArchive.capture(legacy, snapshots, listOf(LegacySourceBinding(key, evidence)), emptyList(), null)
            check(repo.migrate(changed).first { it.legacyKey == key }.state == MigrationState.SOURCE_CHANGED)
            check(repo.migratedVariant(key)!!.paramsJson == current.paramsJson)
        }
        return "Ambiguous media-key aliases, orphan URI, denied source, malformed/oversized payload remain archived; future schema/tool opaque read-only; late unambiguous association works; equal hashes stay separate assets; changed old file cannot overwrite DB authority"
    }

    fun relocationAndConcurrency(): String {
        File(legacy, "$key.json").writeText("{\"brightness\":12}")
        val archive = LegacyMigrationArchive.capture(legacy, snapshots, listOf(LegacySourceBinding(key, evidence)), listOf(key), 0)
        val database = File(root, "concurrent-db")
        lateinit var variant: LibraryVariant
        LibraryPrototypeRepository.openSandbox(database).use { repo -> repo.migrate(archive); variant = repo.migratedVariant(key)!! }
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(1)
        val successes = AtomicInteger()
        val futures = (0..1).map { writer -> executor.submit {
            LibraryPrototypeRepository.openSandbox(database).use { repo ->
                check(latch.await(5, TimeUnit.SECONDS))
                runCatching { repo.saveVariant(variant.id, 1, "{\"brightness\":${20+writer}}") }.onSuccess { successes.incrementAndGet() }
            }
        } }
        latch.countDown(); futures.forEach { it.get(10, TimeUnit.SECONDS) }; executor.shutdown()
        check(successes.get() == 1)
        LibraryPrototypeRepository.openSandbox(database).use { repo ->
            check(repo.variant(variant.id)!!.revision == 2L)
            val moved = File(root, "moved.png").also { fixture.copyTo(it) }
            val relocated = SourceProbe.inspect(context, Uri.fromFile(moved))
            repo.markAvailability(evidence.sourceKey, SourceAvailability.MISSING)
            check(repo.variant(variant.id) != null && repo.session(archive.id)!!.slots.single().second == variant.id)
            check(runCatching { repo.markAvailability(evidence.sourceKey, SourceAvailability.AVAILABLE) }.isFailure)
            check(runCatching { repo.relocate(variant.assetId, relocated.copy(width = 97)) }.isFailure)
            check(runCatching { repo.relocate(variant.assetId, relocated.copy(sha256 = "0".repeat(64))) }.isFailure)
            check(runCatching { repo.relocate(variant.assetId, relocated.copy(mime = "image/jpeg")) }.isFailure)
            check(runCatching { repo.relocate(variant.assetId, relocated.copy(availability = SourceAvailability.PERMISSION_DENIED)) }.isFailure)
            repo.relocate(variant.assetId, relocated)
            check(repo.counts().assets == 1L && repo.counts().locations == 2L)
            repo.migrate(archive)
            check(repo.variant(variant.id)!!.revision == 2L)
        }
        return "Two independent SQLite connections/stale revision race: one winner; missing source retains edit and session, cannot mark available without proof; relocation verifies full hash/size/type/dimensions/orientation, adds location without RAW duplication"
    }

    fun legacyPixelParity(): String {
        val params = EditorParams().apply {
            brightness = 15f; contrast = 22f; temperature = .2f; saturation = 12f
            vignetteAmount = 28f; cropX = .1f; cropW = .8f; flipH = true
            toneCurves = ToneCurves.IDENTITY.with(CurveChannel.RGB, ToneCurve.IDENTITY.add(.5f, .63f))
            colorAdjustments = ColorAdjustments.NEUTRAL.withVibrance(20f).with(HslColor.BLUE, HslBand(12f,-8f,6f))
            channelMixer = ChannelMixer(red = MixerRow(10f,-15f,5f))
            colorWheels = ColorWheels(shadows = WheelAdjustment(220f,25f,-8f))
        }
        File(legacy, "$key.json").writeText(params.toJson().toString())
        val archive = LegacyMigrationArchive.capture(legacy, snapshots, listOf(LegacySourceBinding(key, evidence)), listOf(key), 0)
        val rgba = ShortArray(257*65*4) { (it*373+17).toShort() }
        val start = SystemClock.elapsedRealtimeNanos()
        var bytes = 0
        LibraryPrototypeRepository.openSandbox(File(root,"pixel-db")).use { repo ->
            repo.migrate(archive)
            val restored = EditorParams.fromJson(JSONObject(repo.migratedVariant(key)!!.paramsJson))
            check(params.deltaJson(96,64)==restored.deltaJson(96,64)) { "Migration changed pre-film crop/maths" }
            val before = grade(params); val after = grade(restored)
            for (space in listOf("sRGB","Display P3")) {
                check(ColorPipeline.applyGrade(rgba,257,65,before,space).contentEquals(ColorPipeline.applyGrade(rgba,257,65,after,space)))
                val left = ColorPipeline.applyGradeToBitmap(rgba,257,65,before,space).first
                val right = ColorPipeline.applyGradeToBitmap(rgba,257,65,after,space).first
                val lp=IntArray(257*65);val rp=IntArray(257*65)
                left.getPixels(lp,0,257,0,0,257,65);right.getPixels(rp,0,257,0,0,257,65)
                check(lp.contentEquals(rp));left.recycle();right.recycle()
            }
            val tiffA=ByteArrayOutputStream().also { TiffWriter.writeRgba16(rgba,257,65,before,null,it) }.toByteArray()
            val tiffB=ByteArrayOutputStream().also { TiffWriter.writeRgba16(rgba,257,65,after,null,it) }.toByteArray()
            check(tiffA.contentEquals(tiffB));bytes=tiffA.size
        }
        return "Versioned sidecar/geometry delta exact, composite curve/HSL/mixer/wheel/vignette: sRGB/P3 scalar and native previews exact; TIFF $bytes bytes equal; elapsed_ms=${(SystemClock.elapsedRealtimeNanos()-start)/1e6}"
    }

    private fun grade(p: EditorParams) = Grade(1f+p.temperature*.18f,1f-p.tint*.12f,1f-p.temperature*.18f,
        Math.pow(2.0,p.gradeExposure.toDouble()).toFloat(),p.contrast/100f,p.brightness/100f,1f+p.saturation/100f,
        p.highlights/100f,p.shadows/100f,p.blackPoint/100f,p.whitePoint/100f,FloatArray(3),FloatArray(3),FloatArray(3),
        p.vignetteAmount/100f,p.vignetteSize/100f,p.vignetteFeather/100f,p.toneCurves,p.colorAdjustments,p.channelMixer,p.colorWheels)

    private fun directoryHashes(directory: File) = directory.listFiles().orEmpty().filter { it.isFile }
        .associate { it.name to LegacyMigrationArchive.sha256(it) }
    private fun picture(file: File) {
        val bitmap = Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(96*64) { i -> 0xff000000.toInt() or ((i%96*255/96) shl 16) or ((i/96*255/64) shl 8) or 90 },0,96,0,0,96,64)
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
}
