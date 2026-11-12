# Adapt only the Android build. The shared desktop checkout stays unchanged.
function(theia_android_export_execution engine_dir engine_output pipeline_output)
    set(upstream "${engine_dir}/src/pipeline/engine.cpp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${upstream}")
    file(READ "${upstream}" source)

    set(before_include "#include \"pipeline.hpp\"")
    set(after_include "#include \"pipeline.hpp\"\n#include \"export_execution.hpp\"")
    # This copy exists to cache a negative for future editor reprints. A caller
    # consuming the session can retain the current buffer until the print ends.
    set(before_negative "    Image kept;\n    kept.h = negative.h; kept.w = negative.w; kept.c = 3;")
    set(after_negative "    if (theia::export_execution::one_shot) {\n        out = negative;\n        return true;\n    }\n    Image kept;\n    kept.h = negative.h; kept.w = negative.w; kept.c = 3;")
    # All pixel work is complete. Keep only rgb, which owns the storage needed by
    # materialise even when a stage returned an alias of an earlier image.
    set(before_pack "    if (ok) ok = run_print_for(session, negative, rgb, &session->progress, error);\n    if (ok) ok = materialise(session, rgb, out, error);")
    set(after_pack "    if (ok) ok = run_print_for(session, negative, rgb, &session->progress, error);\n    if (ok && theia::export_execution::one_shot) {\n        const size_t source_bytes = session->source.bytes();\n        const size_t negative_bytes = negative.bytes();\n        negative = Image{};\n        tier_source = Image{};\n        session->tiers.clear();\n        session->source = Image{};\n        theia::export_execution::released_before_readback(source_bytes, negative_bytes);\n    }\n    if (ok) ok = materialise(session, rgb, out, error);")
    set(before_timing "    // Opt-in, because turning it on serialises every node. See Progress.\n    session->progress.detailed = std::getenv(\"SPEKTRAFILM_NODE_TIMINGS\") != nullptr;")
    set(after_timing "    // Detailed export diagnostics are opt-in and do not change pixel operations.\n    session->progress.detailed = std::getenv(\"SPEKTRAFILM_NODE_TIMINGS\") != nullptr || theia::export_execution::detailed;\n    if (theia::export_execution::one_shot) theia::export_execution::cancellation = &session->progress.cancelled;")

    # Metering, film processing and overscan layout have finished before print.
    # A consumed session needs neither its source nor the redundant tier owner
    # during glare, scanner blur and output packing. Keep negative itself alive.
    set(before_print "    if (ok) ok = run_print_for(session, negative, rgb, &session->progress, error);")
    set(after_print "    if (ok && theia::export_execution::one_shot) {\n        theia::export_execution::trace_memory(gpu, \"before_source_release\");\n        tier_source = Image{};\n        session->tiers.clear();\n        session->source = Image{};\n        theia::export_execution::trace_memory(gpu, \"after_source_release\");\n    }\n    if (ok) ok = run_print_for(session, negative, rgb, &session->progress, error);\n    theia::export_execution::trace_memory(gpu, \"after_print\");")

    foreach(anchor include negative pack timing print)
        string(FIND "${source}" "${before_${anchor}}" found)
        if(found EQUAL -1)
            message(FATAL_ERROR "Theia export adapter: shared engine changed at ${anchor}; review its lifetimes")
        endif()
        string(LENGTH "${before_${anchor}}" anchor_length)
        math(EXPR after_anchor "${found} + ${anchor_length}")
        string(SUBSTRING "${source}" ${after_anchor} -1 remainder)
        string(FIND "${remainder}" "${before_${anchor}}" duplicate)
        if(NOT duplicate EQUAL -1)
            message(FATAL_ERROR "Theia export adapter: ambiguous ${anchor} anchor")
        endif()
        string(REPLACE "${before_${anchor}}" "${after_${anchor}}" source "${source}")
    endforeach()

    set(generated "${CMAKE_CURRENT_BINARY_DIR}/generated/engine_android.cpp")
    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/generated")
    set(previous_source "")
    if(EXISTS "${generated}")
        file(READ "${generated}" previous_source)
    endif()
    if(NOT source STREQUAL previous_source)
        file(WRITE "${generated}" "${source}")
    endif()
    set(${engine_output} "${generated}" PARENT_SCOPE)

    # DIR is the last reader of log_e_film. Keeping this plane until grain
    # finishes otherwise overlaps an unused full-frame RGB32 image with grain.
    set(pipeline_upstream "${engine_dir}/src/pipeline/pipeline.cpp")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${pipeline_upstream}")
    file(READ "${pipeline_upstream}" pipeline_source)
    set(before_couplers "bool Pipeline::film_couplers(const Chain& in, Chain& out, std::string& error) {\n    Image next;\n    SPK_NODE(node_dir_couplers(in.cur, in.log_e_film, next, error));\n    out = in;\n    out.cur = next;\n    return true;\n}")
    set(after_couplers "bool Pipeline::film_couplers(const Chain& in, Chain& out, std::string& error) {\n    Image next;\n    SPK_NODE(node_dir_couplers(in.cur, in.log_e_film, next, error));\n    out = in;\n    out.cur = next;\n    if (theia::export_execution::one_shot) out.log_e_film = Image{};\n    return true;\n}")
    string(FIND "${pipeline_source}" "${before_couplers}" couplers_found)
    if(couplers_found EQUAL -1)
        message(FATAL_ERROR "Theia export adapter: DIR lifetime changed; review its final reader")
    endif()
    string(REPLACE "#include \"pipeline.hpp\"" "#include \"pipeline.hpp\"\n#include \"export_execution.hpp\"" pipeline_source "${pipeline_source}")
    string(REPLACE "${before_couplers}" "${after_couplers}" pipeline_source "${pipeline_source}")
    # run_film kept its prefix result alive throughout every later stage.
    # Transfer that owner into the chain so finishing the first stage can
    # actually release the input instead of retaining another whole plane.
    set(before_segment "    if (!film_segment(cur, out, error)) return false;")
    set(after_segment "    if (theia::export_execution::one_shot) {\n        Chain chain;\n        chain.cur = std::move(cur);\n        for (size_t i = 0; i < kFilmStageCount; ++i) {\n            Chain next;\n            if (!(this->*kFilmStages[i].run)(chain, next, error)) return false;\n            chain = std::move(next);\n        }\n        out = std::move(chain.cur);\n    } else if (!film_segment(cur, out, error)) return false;")
    string(FIND "${pipeline_source}" "${before_segment}" segment_found)
    if(segment_found EQUAL -1)
        message(FATAL_ERROR "Theia export adapter: film prefix ownership changed; review its last reader")
    endif()
    string(REPLACE "${before_segment}" "${after_segment}" pipeline_source "${pipeline_source}")
    # Every existing node already flushes. Sample after that boundary so the
    # first rise in the high-water counter identifies the allocating node.
    set(before_flush "        if (!gpu_->flush(error)) return false;                             \\")
    set(after_flush "${before_flush}\n        theia::export_execution::trace_memory(gpu_, #call);                 \\")
    string(FIND "${pipeline_source}" "${before_flush}" flush_found)
    if(flush_found EQUAL -1)
        message(FATAL_ERROR "Theia export adapter: node flush changed; review memory diagnostics")
    endif()
    string(REPLACE "${before_flush}" "${after_flush}" pipeline_source "${pipeline_source}")
    set(pipeline_generated "${CMAKE_CURRENT_BINARY_DIR}/generated/pipeline_android.cpp")
    set(previous_pipeline "")
    if(EXISTS "${pipeline_generated}")
        file(READ "${pipeline_generated}" previous_pipeline)
    endif()
    if(NOT pipeline_source STREQUAL previous_pipeline)
        file(WRITE "${pipeline_generated}" "${pipeline_source}")
    endif()
    set(${pipeline_output} "${pipeline_generated}" PARENT_SCOPE)
endfunction()
