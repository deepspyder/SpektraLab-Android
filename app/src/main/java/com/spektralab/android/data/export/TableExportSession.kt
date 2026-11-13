package com.spektralab.android.data.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import androidx.core.content.res.ResourcesCompat
import com.spektralab.android.R
import com.spektralab.android.core.model.*
import com.spektralab.android.data.library.SourceProbe
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.engine.RawDecoder
import java.io.Closeable
import java.io.File
import java.nio.file.Files

internal data class TableExportInput(
    val key: String,
    val source: TableSource,
    val params: EditSnapshot?,
    val evidence: SourceEvidence,
    val requestedEdge: Int,
)

/** One immutable project export transaction. Never reads the viewing-preview cache. */
internal class TableExportSession private constructor(
    private val context: Context,
    val plan: TableExportPlan,
    val inputs: List<TableExportInput>,
    private val workspace: File,
    private val check: () -> Unit,
) : Closeable {
    enum class Checkpoint { SOURCE_STAGED, COMPOSED, DESTINATION_OPENED, ENCODED }
    internal var checkpoint: (Checkpoint) -> Unit = {}
    private val staged = mutableMapOf<String, File>()
    private var stagedBytes = 0L

    fun stage(input: TableExportInput, bitmap: Bitmap) {
        check()
        val actual = plan.items.filter { it.source.uri == input.key }.map { TableComposition.fit(bitmap.width, bitmap.height, it) }
        check(actual.all { bitmap.width + 1 >= it.width() * plan.scale && bitmap.height + 1 >= it.height() * plan.scale }) { "TABLE_EXPORT_DETAIL" }
        val edge = input.requestedEdge.coerceAtLeast(1)
        val long = maxOf(bitmap.width, bitmap.height)
        val small = if (long > edge) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width.toLong() * edge / long).toInt().coerceAtLeast(1),
            (bitmap.height.toLong() * edge / long).toInt().coerceAtLeast(1), true) else bitmap
        val file = File(workspace, "source-${staged.size}.png")
        try {
            file.outputStream().use { check(small.compress(Bitmap.CompressFormat.PNG, 100, it)) { "TABLE_EXPORT_WRITE" } }
            check(); verify(input, bytes = true)
            stagedBytes += file.length()
            check(stagedBytes <= MAX_STAGE_BYTES) { "TABLE_EXPORT_CAPACITY" }
            staged[input.key] = file
            checkpoint(Checkpoint.SOURCE_STAGED)
        } finally { if (small !== bitmap) small.recycle() }
    }

    fun publish(onCommitted: () -> Unit): String {
        check(staged.size == inputs.size) { "TABLE_EXPORT_SOURCE" }
        inputs.forEach { verify(it, bytes = true) }
        check(StatFs(context.cacheDir.path).availableBytes > plan.rgbaBytes + 32L * 1024 * 1024) { "TABLE_EXPORT_DISK" }
        val font = requireNotNull(ResourcesCompat.getFont(context, R.font.resource_han_rounded_regular))
        var previous: Bitmap? = null
        try {
            checkCapacity(plan.rgbaBytes + 32L * 1024 * 1024)
            val output = TableComposition.render(plan, font, check) { item ->
                // One source bitmap at a time, including repeated placements in different z slots.
                previous?.recycle()
                BitmapFactory.decodeFile(staged.getValue(item.source.uri).path)?.also { previous = it }
                    ?: error("TABLE_EXPORT_SOURCE")
            }
            try {
                checkpoint(Checkpoint.COMPOSED)
                inputs.forEach { verify(it, bytes = false) }
                val png = plan.request.options.format == TableOutputFormat.PNG
                val name = "Theia_Project_${System.currentTimeMillis()}_${System.nanoTime()}.${if (png) "png" else "jpg"}"
                return ExportPublisher(context, {
                    check(); inputs.forEach { verify(it, bytes = true) }
                }, onCommitted).write(name, if (png) "image/png" else "image/jpeg") { stream ->
                    checkpoint(Checkpoint.DESTINATION_OPENED)
                    check(output.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, stream)) { "TABLE_EXPORT_WRITE" }
                    checkpoint(Checkpoint.ENCODED)
                    check()
                }
            } finally { output.recycle() }
        } finally { previous?.recycle() }
    }

    private fun verify(input: TableExportInput, bytes: Boolean) {
        check()
        val current = EditStore.resolveTableSource(context, input.source)
        check(current.uri == input.source.uri && current.editRevision == input.source.editRevision) { "TABLE_EXPORT_CHANGED" }
        val params = EditStore.load(context, Uri.parse(current.uri))?.let(EditSnapshot::capture)
        check(params == input.params) { "TABLE_EXPORT_CHANGED" }
        if (bytes) {
            val source = SourceProbe.inspect(context, EditStore.editSourceUri(context, Uri.parse(current.uri)), check)
            check(source.verified && source.sha256 == input.evidence.sha256 && source.size == input.evidence.size) { "TABLE_EXPORT_CHANGED" }
        }
    }

    override fun close() {
        // Only this transaction's freshly created, app-private files can be removed.
        workspace.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
        workspace.delete()
    }

    companion object {
        const val MAX_INPUT_EDGE = 4096
        const val MAX_STAGE_BYTES = 384L * 1024 * 1024
        fun checkSourceSize(width: Int, height: Int) {
            val pixels = width.toLong() * height
            check(width in 1..MAX_INPUT_EDGE && height in 1..MAX_INPUT_EDGE && pixels <= MAX_INPUT_EDGE.toLong() * MAX_INPUT_EDGE) { "TABLE_EXPORT_CAPACITY" }
            checkCapacity(pixels * 4 + 32L * 1024 * 1024)
        }
        fun checkCapacity(required: Long) {
            val runtime = Runtime.getRuntime()
            check(runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()) > required) { "TABLE_EXPORT_CAPACITY" }
        }
        fun prepare(context: Context, request: TableExportRequest, check: () -> Unit,
            progress: (Int, Int) -> Unit): TableExportSession {
            val plan = request.plan()
            checkCapacity(plan.rgbaBytes * 2 + 32L * 1024 * 1024)
            check(StatFs(context.cacheDir.path).availableBytes > 128L * 1024 * 1024 + plan.rgbaBytes) { "TABLE_EXPORT_DISK" }
            val sources = plan.items.map { it.source }.distinctBy { it.uri }
            val inputs = sources.mapIndexed { index, recorded ->
                check(); progress(index, sources.size)
                val resolved = EditStore.resolveTableSource(context, recorded)
                val source = if (recorded.assetId == null) EditStore.tableSource(context, resolved) else resolved
                plan.items.filter { it.source.uri == recorded.uri }.forEach { placement ->
                    check(placement.source.assetId == recorded.assetId && placement.source.variantId == recorded.variantId) { "TABLE_EXPORT_CHANGED" }
                    check(request.options.latestEdits || placement.source.editRevision == source.editRevision) { "TABLE_EXPORT_STALE" }
                }
                check(request.options.latestEdits || recorded.assetId != null || source.assetId == null) { "TABLE_EXPORT_STALE" }
                check(request.options.latestEdits || source.editRevision == recorded.editRevision) { "TABLE_EXPORT_STALE" }
                val uri = Uri.parse(source.uri)
                val params = EditStore.load(context, uri)
                val original = EditStore.editSourceUri(context, uri)
                val evidence = SourceProbe.inspect(context, original, check)
                check(evidence.verified) { "TABLE_EXPORT_SOURCE" }
                recorded.assetId?.let { asset ->
                    val saved = EditStore.savedPhoto(context, asset) ?: error("TABLE_EXPORT_SOURCE")
                    if (saved.sourceSha256 != null) EditStore.checkSavedSource(context, saved, evidence)
                }
                check(params != null || !RawDecoder.isRaw(context, original, evidence.displayName)) { "TABLE_EXPORT_RAW_UNEDITED" }
                if (params == null) {
                    val header = ByteArray(26)
                    val read = context.contentResolver.openInputStream(original)?.use { stream ->
                        var count = 0
                        while (count < header.size) { val next = stream.read(header, count, header.size - count); if (next <= 0) break; count += next }
                        count
                    } ?: 0
                    // PNG IHDR sample depth follows the signature, chunk prefix and dimensions.
                    val png = read == 26 && header.take(8) == listOf(137,80,78,71,13,10,26,10).map(Int::toByte)
                    check(!(png && (header[24].toInt() and 255) > 8) && evidence.mime != "image/tiff") { "TABLE_EXPORT_PRECISION" }
                }
                check(params?.unreadableColorWheels == null && params?.unreadableChannelMixer == null &&
                    params?.unreadableColorAdjustments == null && params?.unreadableToneCurves == null) { "TABLE_EXPORT_UNSUPPORTED" }
                val edge = plan.sourceEdge(recorded.uri)
                check(edge <= MAX_INPUT_EDGE) { "TABLE_EXPORT_CAPACITY" }
                TableExportInput(recorded.uri, source, params?.let(EditSnapshot::capture), evidence, edge)
            }
            val estimated = inputs.sumOf { it.requestedEdge.toLong() * it.requestedEdge * 4 }
            check(estimated <= MAX_STAGE_BYTES && StatFs(context.cacheDir.path).availableBytes > estimated + 128L * 1024 * 1024) { "TABLE_EXPORT_DISK" }
            return TableExportSession(context, plan, inputs,
                Files.createTempDirectory(context.cacheDir.toPath(), "table-export-").toFile(), check)
        }
    }
}
