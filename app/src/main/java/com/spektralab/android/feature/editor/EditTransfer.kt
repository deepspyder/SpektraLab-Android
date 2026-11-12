package com.spektralab.android.feature.editor

import android.net.Uri
import com.spektralab.android.core.model.EditSnapshot
import com.spektralab.android.core.model.EditTemplate
import com.spektralab.android.core.model.EditTransferNotice

/** The exact preflight result shown to the user is the payload committed on Apply. */
internal data class EditTransferTarget(
    val uri: Uri,
    val name: String,
    val before: EditSnapshot,
    val expectedJson: String?,
    val after: EditSnapshot?,
    val notices: Set<EditTransferNotice> = emptySet(),
    val problem: String? = null,
    val paperName: String? = null,
) {
    val changed: Boolean get() = after != null && before != after
}

internal data class EditTransferPreview(val template: EditTemplate, val targets: List<EditTransferTarget>) {
    val changes: List<EditTransferTarget> get() = targets.filter { it.changed }
}
