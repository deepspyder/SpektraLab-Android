package com.spektralab.android.feature.lighttable

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.spektralab.android.core.model.LightTableScene
import com.spektralab.android.core.model.TablePlacement
import com.spektralab.android.core.model.TableSource
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Transient gesture state; one completed operation adds one bounded history entry. */
@Stable
internal class FilmCanvasState(zoom: Float = 1f, x: Float = 0f, y: Float = 0f) {
    var zoom by mutableFloatStateOf(zoom); private set
    private var scenePan by mutableStateOf(Offset(x, y))
    var viewportFit by mutableFloatStateOf(1f)
    val pan get() = scenePan * viewportFit
    internal var focus = Offset.Zero
    internal var origin = Offset.Zero
    val photoPositions = mutableStateMapOf<String, Offset>()
    val photoOrder = mutableStateListOf<String>()
    val rotations = mutableStateMapOf<String, Float>()
    val photoSizes = mutableStateMapOf<String, Size>()
    var members by mutableStateOf<List<TablePlacement>>(emptyList()); private set
    var lightOn by mutableStateOf(true); private set
    var millimeters by mutableStateOf(false); private set
    var editVersion by mutableLongStateOf(0); private set
    var draggingKey by mutableStateOf<String?>(null); private set
    var joinPreview by mutableStateOf<WallJoin?>(null); private set
    var joinMotion by mutableStateOf<WallJoinMotion?>(null); private set
    var joinFraction by mutableFloatStateOf(1f)
    private val undo = mutableStateListOf<Layout>()
    private val redo = mutableStateListOf<Layout>()
    private var gesture: Layout? = null
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    private data class Layout(val zoom: Float, val pan: Offset, val positions: Map<String, Offset>, val sizes: Map<String, Size>,
        val rotations: Map<String, Float>, val order: List<String>, val members: List<TablePlacement>, val light: Boolean, val millimeters: Boolean)
    private fun layout() = Layout(zoom, scenePan, photoPositions.toMap(), photoSizes.toMap(), rotations.toMap(), photoOrder.toList(), members, lightOn, millimeters)
    private fun apply(value: Layout) {
        interruptJoin(); zoom = value.zoom; scenePan = value.pan
        photoPositions.clear(); photoPositions.putAll(value.positions)
        photoSizes.clear(); photoSizes.putAll(value.sizes)
        rotations.clear(); rotations.putAll(value.rotations)
        photoOrder.clear(); photoOrder.addAll(value.order)
        members = value.members; lightOn = value.light; millimeters = value.millimeters
    }
    fun beginGesture() { if (gesture == null) { interruptJoin(); gesture = layout() } }
    fun finishGesture(cancelled: Boolean = false) {
        val before = gesture ?: return
        gesture = null
        if (cancelled) { apply(before); return }
        if (before != layout()) {
            undo.add(before); if (undo.size > 50) undo.removeAt(0)
            redo.clear(); editVersion++
        }
    }
    fun edit(block: () -> Unit) {
        beginGesture()
        try { block(); finishGesture() }
        catch (error: Throwable) { finishGesture(cancelled = true); throw error }
    }
    fun undo() { if (canUndo) { redo.add(layout()); apply(undo.removeAt(undo.lastIndex)); editVersion++ } }
    fun redo() { if (canRedo) { undo.add(layout()); apply(redo.removeAt(redo.lastIndex)); editVersion++ } }
    fun move(delta: Offset) { scenePan += delta / viewportFit }
    fun reset() = edit { zoom = 1f; scenePan = Offset.Zero }
    fun zoomBy(factor: Float) = edit { transform(focus, Offset.Zero, factor, origin) }
    fun toggleLight() = edit { lightOn = !lightOn }
    fun movePhoto(key: String, initial: Offset, delta: Offset) {
        if (joinMotion != null) interruptJoin()
        photoPositions[key] = (photoPositions[key] ?: initial) + delta
        if (photoOrder.lastOrNull() != key) { photoOrder.remove(key); photoOrder.add(key) }
    }
    fun position(key: String, initial: Offset): Offset {
        val motion = joinMotion?.takeIf { it.join.key == key }
        return if (motion != null) motion.from + (motion.join.position - motion.from) * joinFraction else photoPositions[key] ?: initial
    }
    fun interruptJoin() {
        joinMotion?.let { photoPositions[it.join.key] = position(it.join.key, it.from) }
        joinMotion = null; joinFraction = 1f
    }
    fun beginDrag(key: String) { interruptJoin(); draggingKey = key; joinPreview = null }
    fun previewJoin(join: WallJoin?) { joinPreview = join }
    fun endDrag(snap: Boolean) {
        val join = joinPreview
        draggingKey = null; joinPreview = null
        if (snap && join != null) {
            val from = photoPositions[join.key] ?: join.position
            photoPositions[join.key] = join.position
            joinFraction = 0f; joinMotion = WallJoinMotion(join, from)
        }
    }
    fun finishJoin() { joinFraction = 1f; joinMotion = null }
    fun transform(centroid: Offset, delta: Offset, factor: Float, origin: Offset) {
        val next = (zoom * factor).coerceIn(.35f, 6f)
        scenePan = (centroid - origin - (centroid - origin - pan) * (next / zoom) + delta) / viewportFit
        zoom = next
    }
    fun rotate(key: String, degrees: Float) = edit { rotations[key] = degrees.coerceIn(-180f, 180f) }
    fun layer(key: String, front: Boolean) = edit {
        photoOrder.remove(key)
        if (front) photoOrder.add(key) else photoOrder.add(0, key)
    }
    fun duplicate(key: String) = edit {
        val item = scene().items.first { it.id == key }
        check(members.size < 200) { "TABLE_CAPACITY" }
        val copy = item.copy(id = UUID.randomUUID().toString(), x = item.x + 20f, y = item.y + 20f)
        members = members + copy; photoPositions[copy.id] = Offset(copy.x, copy.y)
        photoSizes[copy.id] = Size(copy.width, copy.height); rotations[copy.id] = copy.rotation; photoOrder.add(copy.id)
    }
    fun remove(key: String) = edit {
        members = members.filterNot { it.id == key }; photoPositions.remove(key)
        photoSizes.remove(key); rotations.remove(key); photoOrder.remove(key)
    }
    fun add(items: List<TablePlacement>) = edit {
        check(members.size + items.size <= 200) { "TABLE_CAPACITY" }
        members = members + items
        items.forEach { photoPositions[it.id] = Offset(it.x, it.y); photoSizes[it.id] = Size(it.width, it.height)
            rotations[it.id] = it.rotation; photoOrder.add(it.id) }
    }
    fun replaceSources(sources: Map<String, TableSource>) {
        members = members.map { it.copy(source = sources[it.id] ?: it.source) }
    }
    /** Explicit mapping only: one scene unit becomes one mm; existing coordinates stay put. */
    fun changeUnits(enabled: Boolean) = edit { millimeters = enabled }
    fun width(key: String, width: Float) = edit {
        require(width.isFinite() && width in 1f..10000f)
        val size = photoSizes.getValue(key)
        require(size.height * width / size.width <= 10000f)
        photoSizes[key] = Size(width, size.height * width / size.width)
    }
    fun scene(): LightTableScene = LightTableScene(members.sortedBy { photoOrder.indexOf(it.id) }.map { item ->
        val point = photoPositions[item.id] ?: Offset(item.x, item.y)
        val size = photoSizes[item.id] ?: Size(item.width, item.height)
        item.copy(x = point.x, y = point.y, width = size.width, height = size.height, rotation = rotations[item.id] ?: item.rotation)
    }, zoom, scenePan.x, scenePan.y, lightOn, millimeters).validated()
    fun restore(scene: LightTableScene) {
        scene.validated()
        apply(Layout(scene.zoom, Offset(scene.panX, scene.panY), scene.items.associate { it.id to Offset(it.x, it.y) },
            scene.items.associate { it.id to Size(it.width, it.height) }, scene.items.associate { it.id to it.rotation },
            scene.items.map { it.id }, scene.items, scene.lightOn, scene.millimeters))
        undo.clear(); redo.clear(); gesture = null; editVersion++
    }
    companion object {
        val Saver = listSaver<FilmCanvasState, String>(save = { state ->
            listOf(state.zoom.toString(), state.scenePan.x.toString(), state.scenePan.y.toString(),
                JSONObject().apply { state.photoPositions.forEach { (key, point) -> put(key, JSONArray(listOf(point.x, point.y))) } }.toString(),
                JSONArray(state.photoOrder.toList()).toString(), state.scene().toJson(),
                JSONObject().apply { state.rotations.forEach { (key, value) -> put(key, value) } }.toString(),
                JSONObject().apply { state.photoSizes.forEach { (key, value) -> put(key, JSONArray(listOf(value.width, value.height))) } }.toString())
        }, restore = { values -> FilmCanvasState(values[0].toFloat(), values[1].toFloat(), values[2].toFloat()).apply {
            val scene = values.getOrNull(5)?.let(LightTableScene::fromJson)
            scene?.let(::restore)
            if (scene == null || scene.items.isEmpty()) {
                val positions = JSONObject(values[3])
                positions.keys().forEach { key -> positions.getJSONArray(key).let { photoPositions[key] = Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
                val order = JSONArray(values[4]); for (i in 0 until order.length()) photoOrder.add(order.getString(i))
                values.getOrNull(6)?.let(::JSONObject)?.let { angles -> angles.keys().forEach { rotations[it] = angles.getDouble(it).toFloat() } }
                values.getOrNull(7)?.let(::JSONObject)?.let { sizes -> sizes.keys().forEach { key ->
                    sizes.getJSONArray(key).let { photoSizes[key] = Size(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
                } }
            }
        } })
    }
}

@Composable
internal fun rememberFilmCanvasState() = rememberSaveable(saver = FilmCanvasState.Saver) { FilmCanvasState() }
