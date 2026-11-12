"""Build a standalone Android export parity executable without adding test JNI."""
import argparse
import re
from pathlib import Path
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Fresh local output directory")
    parser.add_argument("--audit", choices=("fir", "dir", "print", "mixture"), default="fir")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Use a new output directory to preserve previous evidence")
    source_name = "blur_android.cpp" if args.audit in ("fir", "mixture") else "pipeline_android.cpp"
    candidates = list((ROOT / "app/.cxx/RelWithDebInfo").glob("*/x86_64/generated/" + source_name))
    if not candidates:
        parser.error("Build the x86_64 Release APK with the Android export adapters first")
    generated = max(candidates, key=lambda p: p.stat().st_mtime)
    cache = generated.parents[1] / "CMakeCache.txt"
    values = {}
    for line in cache.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith(("#", "//")):
            key, value = line.split("=", 1)
            values[key.split(":", 1)[0]] = value
    engine = Path(values["SPEKTRA_ENGINE_DIR"]).resolve()
    compiler_value = values.get("CMAKE_CXX_COMPILER")
    if not compiler_value:
        for info in generated.parents[1].glob("CMakeFiles/*/CMakeCXXCompiler.cmake"):
            match = re.search(r'set\(CMAKE_CXX_COMPILER "([^"]+)"\)', info.read_text(encoding="utf-8"))
            if match:
                compiler_value = match.group(1)
                break
    if not compiler_value:
        parser.error("Could not resolve the configured NDK C++ compiler")
    compiler = Path(compiler_value)
    toolchain = compiler.parent.parent
    output.mkdir(parents=True)
    if args.audit == "mixture":
        audit_source = generated.read_text(encoding="utf-8")
        anchor = "        if (!gpu_->flush(error)) return false;\n        if (theia::export_execution::one_shot && theia::export_execution::cancellation &&"
        if audit_source.count(anchor) != 1:
            parser.error("Mixture component cancellation boundary changed")
        audit_source = "extern void theia_mixture_audit_checkpoint();\n" + audit_source
        audit_source = audit_source.replace(anchor,
            "        theia_mixture_audit_checkpoint();\n" + anchor)
        generated = output / "blur_audit.cpp"
        generated.write_text(audit_source, encoding="utf-8")
    if args.audit == "dir":
        # Standalone fault injection only: public progress intentionally exposes
        # "rendering" while work is active, so it cannot select an internal node.
        # Never add this cancellation hook to the APK's generated source.
        audit_source = generated.read_text(encoding="utf-8")
        audit_source = "extern thread_local int theia_dir_audit_cancel_checkpoint;\n" + audit_source
        for checkpoint, phase in enumerate(("correction", "diffusion", "combine"), 1):
            anchor = f'theia::export_execution::trace_memory(gpu_, "dir_after_{phase}");'
            if audit_source.count(anchor) != 1:
                parser.error("DIR cancellation checkpoint changed: " + phase)
            audit_source = audit_source.replace(anchor,
                f"if (theia_dir_audit_cancel_checkpoint == {checkpoint} && progress_) "
                "progress_->cancelled = true;\n    " + anchor)
        generated = output / "pipeline_audit.cpp"
        generated.write_text(audit_source, encoding="utf-8")
    if args.audit == "print":
        audit_source = generated.read_text(encoding="utf-8")
        anchor = 'theia::export_execution::trace_memory(gpu_, kPrintStages[i].name);'
        if audit_source.count(anchor) != 1:
            parser.error("Print stage checkpoints changed")
        audit_source = "extern thread_local int theia_print_audit_cancel_checkpoint;\n" + audit_source
        audit_source = audit_source.replace(anchor,
            "if (theia_print_audit_cancel_checkpoint == int(i + 1) && progress_) "
            "progress_->cancelled = true;\n            " + anchor)
        generated = output / "pipeline_audit.cpp"
        generated.write_text(audit_source, encoding="utf-8")
    with ZipFile(ROOT / "app/build/outputs/apk/release/app-release.apk") as apk:
        for name in ("libspektralab.so", "libc++_shared.so"):
            (output / name).write_bytes(apk.read("lib/x86_64/" + name))
    command = [
        str(compiler), "--target=x86_64-linux-android26", "--sysroot=" + str(toolchain / "sysroot"),
        "-std=c++20", "-O2", "-I" + str(ROOT / "app/src/main/cpp"),
        *("-I" + str(engine / path) for path in ("src", "src/core", "src/pipeline", "src/gpu")),
        str(ROOT / {"fir": "tools/native/export_fir_bands_audit.cpp",
                    "dir": "tools/native/export_dir_audit.cpp",
                    "print": "tools/native/export_print_audit.cpp",
                    "mixture": "tools/native/export_mixture_audit.cpp"}[args.audit]), str(generated),
        "-L" + str(output), "-lspektralab", "-lc++_shared", "-llog",
        "-Wl,-rpath,$ORIGIN", "-o", str(output / ("export-" + args.audit + "-audit")),
    ]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (output / "build.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        print(result.stdout + result.stderr)
        raise SystemExit(result.returncode)
    print("Built " + str(output / ("export-" + args.audit + "-audit")))


if __name__ == "__main__":
    main()
