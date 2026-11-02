#!/usr/bin/env python3
"""把引擎资源同步到 Android assets（等价于 engine 的 spektralab_vulkan_resources 目标）。

用法:
    python tools/sync_resources.py [ENGINE_DIR] [OUT_DIR]

默认:
    ENGINE_DIR = ../SpektraLab-Windows/engine
    OUT_DIR    = app/src/main/assets/engine/resources

SPIR-V 着色器不在此脚本里（需要 glslang），见 cpp/engine/README.md。
"""
import shutil
import sys
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
DEFAULT_ENGINE = (ROOT.parent / "SpektraLab-Windows" / "engine").resolve()
DEFAULT_OUT = (ROOT / "app" / "src" / "main" / "assets" / "engine" / "resources").resolve()


def copy_file(src: Path, dst: Path):
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def main() -> None:
    engine = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_ENGINE
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUT

    if not (engine / "resources" / "spektrafilm_constants.bin").is_file():
        sys.exit(f"引擎资源不存在: {engine / 'resources'}")

    # 1. 基础资源（28 profiles、constants.bin、print_luts.json、neutral_print_filters.json、io/sRGB.icc）
    shutil.copytree(engine / "resources", out, dirs_exist_ok=True)

    # 2. 覆盖 5 个 B&W profiles（resources_product/profiles → profiles/）
    shutil.copytree(engine / "resources_product" / "profiles", out / "profiles", dirs_exist_ok=True)

    # 3-7. 重命名的单文件
    copy_file(engine / "resources_product" / "NOTICE.md", out / "Product-profiles-NOTICE.md")
    copy_file(engine / "resources_product" / "fonts" / "LICENSE", out / "Imprint-fonts-LICENSE.txt")
    copy_file(engine / "third_party" / "stb" / "LICENSE", out / "stb-LICENSE.txt")
    copy_file(engine / "resources_product" / "paper_grades.json", out / "paper_grades.json")
    copy_file(engine / "resources_product" / "neutral_print_filters.json",
              out / "product-neutral-print-filters.json")

    # 8. DI 资源（可选，数字中间片导出用）
    di = engine.parent / "modern_UI" / "Spektrafilm" / "Spektrafilm" / "Resources" / "DI"
    if di.is_dir():
        shutil.copytree(di, out / "DI", dirs_exist_ok=True)

    profiles = list((out / "profiles").glob("*.json"))
    print(f"资源已同步 -> {out}")
    print(f"profiles: {len(profiles)} 个")
    for name in ("spektrafilm_constants.bin", "print_luts.json", "paper_grades.json"):
        print(f"  {'OK' if (out / name).is_file() else 'MISSING'}  {name}")


if __name__ == "__main__":
    main()
