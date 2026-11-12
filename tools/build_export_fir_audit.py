"""Build the standalone Android FIR parity executable without adding test JNI to the app."""
import argparse
import re
from pathlib import Path
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Fresh local output directory")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        parser.error("Use a new output directory to preserve previous evidence")
    candidates = list((ROOT / "app/.cxx/RelWithDebInfo").glob("*/x86_64/generated/blur_android.cpp"))
    if not candidates:
        parser.error("Build the x86_64 Release APK with the Android FIR adapter first")
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
    with ZipFile(ROOT / "app/build/outputs/apk/release/app-release.apk") as apk:
        for name in ("libspektralab.so", "libc++_shared.so"):
            (output / name).write_bytes(apk.read("lib/x86_64/" + name))
    command = [
        str(compiler), "--target=x86_64-linux-android26", "--sysroot=" + str(toolchain / "sysroot"),
        "-std=c++20", "-O2", "-I" + str(ROOT / "app/src/main/cpp"),
        *("-I" + str(engine / path) for path in ("src", "src/core", "src/pipeline", "src/gpu")),
        str(ROOT / "tools/native/export_fir_bands_audit.cpp"), str(generated),
        "-L" + str(output), "-lspektralab", "-lc++_shared", "-llog",
        "-Wl,-rpath,$ORIGIN", "-o", str(output / "export-fir-audit"),
    ]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (output / "build.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        print(result.stdout + result.stderr)
        raise SystemExit(result.returncode)
    print("Built " + str(output / "export-fir-audit"))


if __name__ == "__main__":
    main()
