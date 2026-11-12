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
    set(after_print "    if (ok && theia::export_execution::one_shot) {\n        theia::export_execution::trace_memory(gpu, \"before_source_release\");\n        tier_source = Image{};\n        session->tiers.clear();\n        session->source = Image{};\n        theia::export_execution::trace_memory(gpu, \"after_source_release\");\n    }\n    if (ok) {\n        theia::export_execution::PrintInputScope<Image> input_scope(negative);\n        ok = run_print_for(session, negative, rgb, &session->progress, error);\n    }\n    theia::export_execution::trace_memory(gpu, \"after_print\");")

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
    include("${CMAKE_CURRENT_FUNCTION_LIST_DIR}/AndroidExportDir.cmake")
    theia_android_export_dir("${pipeline_source}" after_segment)
    string(FIND "${pipeline_source}" "${before_segment}" segment_found)
    if(segment_found EQUAL -1)
        message(FATAL_ERROR "Theia export adapter: film prefix ownership changed; review its last reader")
    endif()
    string(REPLACE "${before_segment}" "${after_segment}" pipeline_source "${pipeline_source}")
    # run_print previously borrowed the engine's negative for the whole print.
    # Transfer that exact owner only after constants and contrast-mask setup;
    # the first stage can then retire it before glare allocates its field.
    set(print_template "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/templates/ExportPrintSegment.cpp.in")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${print_template}")
    file(READ "${print_template}" after_print_segment)
    set(before_print_segment "    const bool ok = print_segment(cmy, out, error);")
    string(FIND "${pipeline_source}" "${before_print_segment}" print_segment_found)
    if(print_segment_found EQUAL -1)
        message(FATAL_ERROR "Theia export adapter: print setup boundary changed; review its final readers")
    endif()
    string(REPLACE "${before_print_segment}" "${after_print_segment}" pipeline_source "${pipeline_source}")
    # Grain's affine is the final reader of the unshifted realisation. Keeping
    # that plane during the final blur would recreate DIR's five-plane peak.
    foreach(grain_result scaled shifted)
        set(before_grain "        if (!blur_.affine(grain, s, t0, ${grain_result}, error)) return false;")
        if(grain_result STREQUAL shifted)
            set(before_grain "    if (!blur_.affine(grain, s, t0, shifted, error)) return false;")
        endif()
        string(FIND "${pipeline_source}" "${before_grain}" grain_found)
        if(grain_found EQUAL -1)
            message(FATAL_ERROR "Theia export adapter: grain ${grain_result} reader changed")
        endif()
        set(after_grain "${before_grain}\n    if (theia::export_execution::one_shot) {\n        if (!gpu_->flush(error)) return false;\n        grain = Image{};\n        theia::export_execution::trace_memory(gpu_, \"grain_after_${grain_result}\");\n    }")
        string(REPLACE "${before_grain}" "${after_grain}" pipeline_source "${pipeline_source}")
    endforeach()
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
