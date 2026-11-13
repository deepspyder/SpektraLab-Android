package com.spektralab.android

import android.content.Context
import android.os.Process
import com.spektralab.android.data.library.LightTableProjectStore
import java.io.File

internal object LightTableProcessAudit {
    fun terminate(context: Context, output: File, mode: String): String {
        val root = File(output, "table-process/$mode").apply { mkdirs() }
        val scene = LightTableStorageAudit.scene()
        val store = LightTableProjectStore(root)
        val original = store.save(null, "持久桌面", scene)
        File(root, "id.txt").writeText(original.id)
        File(root, "before.txt").writeText(scene.toJson())
        val checkpoint = if (mode.endsWith("after")) LightTableProjectStore.Checkpoint.COMMITTED else LightTableProjectStore.Checkpoint.WRITTEN
        store.checkpoint = {
            if (it == checkpoint) {
                File(root, "checkpoint.txt").writeText(it.name)
                Process.killProcess(Process.myPid())
                error("Process termination failed")
            }
        }
        if (mode.startsWith("delete")) store.delete(original)
        else store.save(original, "保存后的桌面", scene.copy(lightOn = true, panX = 45f))
        error("Checkpoint not reached")
    }

    fun verify(context: Context, output: File): String {
        for (mode in listOf("save-during", "save-after", "delete-during", "delete-after")) {
            val root = File(output, "table-process/$mode")
            val id = File(root, "id.txt").readText()
            val before = com.spektralab.android.core.model.LightTableScene.fromJson(File(root, "before.txt").readText())
            LightTableProjectStore(root).use { store ->
                if (mode == "delete-after") check(store.list().isEmpty())
                else {
                    check(store.list().size == 1)
                    val project = store.read(id)
                    check(project.scene == if (mode == "save-after") before.copy(lightOn = true, panX = 45f) else before)
                    check(project.revision == if (mode == "save-after") 2L else 1L)
                }
            }
        }
        return "Four real process deaths before/after project save/delete commit; whole prior scene or complete new scene, stable project identity, no partial item/order/rotation/size changes and safe delete recovery passed"
    }
}
