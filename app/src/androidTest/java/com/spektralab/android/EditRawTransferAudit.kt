package com.spektralab.android

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.spektralab.android.core.model.EditGroup
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.data.local.EditStore
import com.spektralab.android.feature.editor.EditorViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** A copied decode adjustment must match the established direct RAW-WB path exactly. */
internal class EditRawTransferAudit(private val test: Instrumentation, private val output: File) {
    fun run(): String {
        val context = test.targetContext
        val file = File(output, "perf-z6ii.NEF")
        check(file.isFile)
        val uri = Uri.fromFile(file)
        val previous = EditStore.load(context, uri)
        val activity = test.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var vm: EditorViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[EditorViewModel::class.java] }
        fun idle() = await {
            vm.hasPhoto && !vm.busy && vm.lastResult != null && !vm.isApplyingEdits && vm.renderTaskState.active == null &&
                listOf("renderJob", "wbJob", "cameraWbJob", "photoLoadJob").none { name ->
                    val field = EditorViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
                    (field.get(vm) as? Job)?.isActive == true
                }
        }
        val base = EditorParams().apply { grainActive = false; glareActive = false }
        try {
            await { vm.engine != null && !vm.busy }
            EditStore.save(context, uri, base)
            test.runOnMainSync { vm.openPhotos(listOf(uri)) }
            idle()
            val asShot = vm.sourceBitmap
            test.runOnMainSync {
                vm.params.cameraWbCustom = true
                vm.params.cameraWbKelvin = 6800f
                vm.params.cameraWbTint = 12f
                vm.scheduleCameraWb()
            }
            idle()
            check(vm.errorMessage == null && vm.sourceBitmap !== asShot)
            val expected = vm.lastResult!!.rgba16.clone()
            val desired = vm.params.copy()
            EditStore.save(context, uri, base)
            test.runOnMainSync { vm.openPhoto(uri) }
            idle()
            check(!vm.params.cameraWbCustom)
            val before = vm.sourceBitmap
            val template = EditTemplate.capture(desired, setOf(EditGroup.DECODE))
            val preview = runBlocking { withContext(Dispatchers.Main) { vm.previewEditTransfer(template, listOf(uri)) } }
            check(preview.changes.size == 1 && preview.targets.single().notices.isEmpty())
            test.runOnMainSync { vm.applyEditTransfer(preview) }
            idle()
            check(vm.errorMessage == null && vm.editTransferError == null)
            check(vm.params.cameraWbCustom && vm.params.cameraWbKelvin == 6800f && vm.params.cameraWbTint == 12f)
            check(vm.sourceBitmap !== before && vm.lastResult!!.rgba16.contentEquals(expected)) { "Copied RAW WB differs from direct path" }
            check(EditStore.load(context, uri)!!.cameraWbKelvin == 6800f)
            test.runOnMainSync { vm.undo() }
            idle(); check(!vm.params.cameraWbCustom)
            test.runOnMainSync { vm.redo() }
            idle(); check(vm.lastResult!!.rgba16.contentEquals(expected))
            return "Real Z6II RAW: copied 6800K/tint12 redecodes and equals direct WB path for every preview U16 sample; sidecar and undo/redo match, PNG partial-skip covered separately"
        } finally {
            if (previous != null) EditStore.save(context, uri, previous)
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            check(SystemClock.elapsedRealtime() - start < 120000) { "RAW transfer timed out" }
            SystemClock.sleep(20)
        }
    }
}
