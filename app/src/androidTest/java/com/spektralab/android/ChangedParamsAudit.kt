package com.spektralab.android

import android.content.Context
import com.spektralab.android.core.model.EditorParams
import com.spektralab.android.engine.EngineClient
import com.spektralab.android.engine.SpkException

/** A real full render is the oracle for the restored native reprint path. */
internal object ChangedParamsAudit {
    fun run(context: Context): String {
        val width = 640; val height = 480
        val input = FloatArray(width * height * 3) { index ->
            when (index % 3) { 0 -> (index / 3 % width + 1f) / width;
                1 -> (index / 3 / width + 1f) / height; else -> .18f }
        }
        EngineClient.create(context).use { engine ->
            val base = EditorParams().apply { grainActive = false; glareActive = false }
            engine.open(input, width, height, 3, base.openJson(width, height))
            engine.render("live")
            val edits: List<EditorParams.() -> Unit> = listOf(
                { printExposure = 1.2f }, { yFilterShift = .2f }, { mFilterShift = -.2f },
                { preflashExposure = .01f }, { extendedDynamicRange = true },
                { paperId = "fujifilm_crystal_archive_typeii" },
            )
            for (edit in edits) {
                val p = base.copy().apply(edit)
                engine.setParams(base.deltaJson(width, height)); engine.render("live")
                engine.setChangedParams(p.deltaJson(width, height))
                val cached = engine.render("live")
                check(cached.reprint && cached.negativeWasCached) { "Print edit unnecessarily rebuilt the negative" }
                engine.setParams(p.deltaJson(width, height))
                val full = engine.renderFull("live")
                check(cached.width == full.width && cached.height == full.height && cached.rgba16.contentEquals(full.rgba16)) {
                    "Reprint changed U16 pixels for " + p.toJson()
                }
                // Reapply after an out-of-band native mutation; no stale host snapshot is trusted.
                engine.setParams(org.json.JSONObject().put("print_exposure", .8).toString())
                engine.setChangedParams(p.deltaJson(width, height))
                check(engine.render("live").rgba16.contentEquals(full.rgba16))
            }
            engine.open(input, width, height, 3, base.openJson(width, height))
            engine.render("live")
            val capture = base.copy().apply { exposureEv = .5f }
            engine.setChangedParams(capture.deltaJson(width, height))
            check(!engine.render("live").reprint) { "Capture edit reused an obsolete negative" }
            val film = capture.copy().apply { filmId = "kodak_gold_200" }
            engine.setChangedParams(film.deltaJson(width, height))
            val switched = engine.render("live")
            check(!switched.reprint)
            engine.open(input, width, height, 3, film.openJson(width, height))
            check(switched.rgba16.contentEquals(engine.render("live").rgba16))
            for (invalid in listOf(org.json.JSONObject().put("unknown_parameter", 1).toString(),
                org.json.JSONObject().put("print_exposure", "bad").toString(), "{bad")) {
                check(runCatching { engine.setChangedParams(invalid) }.exceptionOrNull() is SpkException)
            }
        }
        return "Six print/paper edits reuse the negative and equal full U16 renders; capture/film rebuild; reopen and native mutation stay coherent; invalid deltas still fail"
    }
}
