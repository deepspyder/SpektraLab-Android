package com.spektralab.android.feature.editor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spektralab.android.R
import com.spektralab.android.core.model.EditPreset
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.data.local.EditPresetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small metadata operations stay off the preview thread and out of the renderer. */
internal class EditPresetController(private val context: Context, private val scope: CoroutineScope) {
    var items by mutableStateOf<List<EditPreset>>(emptyList())
        private set
    var unsupported by mutableStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set
    var pending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var revision by mutableStateOf(0)
        private set

    init { reload() }

    fun reload() = update { EditPresetStore.load(context) }
    fun save(name: String, template: EditTemplate, replaceId: String? = null) =
        update { EditPresetStore.save(context, name, template, replaceId) }
    fun rename(preset: EditPreset, name: String) = save(name, preset.template, preset.id)
    fun delete(id: String) = update { EditPresetStore.delete(context, id) }

    private fun update(operation: () -> EditPresetStore.Catalog) {
        if (pending) return
        pending = true
        error = null
        scope.launch {
            try {
                val catalog = withContext(Dispatchers.IO) { operation() }
                items = catalog.presets
                unsupported = catalog.unsupported
                ready = true
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = context.getString(R.string.edit_presets_storage_error)
            } finally { pending = false }
        }
    }
}
