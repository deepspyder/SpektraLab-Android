#!/usr/bin/env python3
"""把引擎的 Vulkan 计算着色器（GLSL .comp）编译成 SPIR-V（.spv）。

SPIR-V 是跨平台字节码，只需编译一次。需要 glslangValidator（Vulkan SDK 自带，
或从 https://github.com/KhronosGroup/glslang/releases 下载）。

用法:
    python tools/compile_shaders.py [GLSLANG_BIN] [OUT_DIR]

默认:
    GLSLANG_BIN = 自动查找（glslangValidator / glslang，或 %VULKAN_SDK%/Bin）
    OUT_DIR     = app/src/main/assets/engine/resources/vulkan
"""
import shutil
import subprocess
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
DEFAULT_OUT = (ROOT / "app" / "src" / "main" / "assets" / "engine" / "resources" / "vulkan").resolve()
SHADER_DIR = (ROOT.parent / "SpektraLab-Windows" / "engine" / "src" / "shaders" / "vulkan").resolve()
ANDROID_SHADER_DIR = ROOT / "app" / "src" / "main" / "cpp" / "shaders" / "vulkan"


def find_glslang(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    for name in ("glslangValidator", "glslang"):
        p = shutil.which(name)
        if p:
            return p
    # Windows 上常见位置（Vulkan SDK）
    import os
    sdk = os.environ.get("VULKAN_SDK")
    if sdk:
        for sub in ("Bin", "bin"):
            p = Path(sdk) / sub / "glslangValidator.exe"
            if p.is_file():
                return str(p)
    return None


def main() -> None:
    glslang = find_glslang(sys.argv[1] if len(sys.argv) > 1 else None)
    if not glslang:
        sys.exit("未找到 glslangValidator。请安装 Vulkan SDK 或下载 glslang，"
                 "或用参数指定二进制路径。")

    out = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUT
    out.mkdir(parents=True, exist_ok=True)

    sources = {shader.name: shader for shader in SHADER_DIR.glob("*.comp")}
    sources.update({shader.name: shader for shader in ANDROID_SHADER_DIR.glob("*.comp")})
    shaders = sorted(sources.values())
    if not shaders:
        sys.exit(f"没找到着色器: {SHADER_DIR}")

    failed = 0
    for shader in shaders:
        spv = out / (shader.stem + ".spv")
        cmd = [glslang, "-V", "--target-env", "vulkan1.0",
               f"-I{SHADER_DIR}", "-o", str(spv), str(shader)]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode == 0:
            print(f"OK   {spv.name}")
        else:
            failed += 1
            print(f"FAIL {shader.name}\n{r.stderr.strip()[:400]}")

    if failed:
        sys.exit(f"{failed}/{len(shaders)} 个着色器编译失败")
    print(f"完成：{len(shaders)} 个 SPIR-V -> {out}")


if __name__ == "__main__":
    main()
