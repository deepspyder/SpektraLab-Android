#!/usr/bin/env python3
"""Check Theia's source-package layout and dependency direction without an Android SDK."""
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java"
BASE_PACKAGE = "com.spektralab.android"
errors = []

for source in sorted(SOURCE.rglob("*.kt")):
    text = source.read_text(encoding="utf-8")
    declared = re.search(r"^package (\S+)", text, re.MULTILINE)
    expected = ".".join(source.relative_to(SOURCE).parts[:-1])
    if not declared or declared.group(1) != expected:
        errors.append(f"{source.relative_to(ROOT)}: package must match {expected}")
        continue

    package = declared.group(1)
    dependencies = re.findall(r"^import (com\.spektralab\.android\.[\w.*]+)", text, re.MULTILINE)
    for dependency in dependencies:
        # Shared code must stay independent of application routes and feature UI.
        if package.startswith(tuple(BASE_PACKAGE + "." + part for part in ("core.", "data.", "engine.", "ui."))):
            if dependency.startswith((BASE_PACKAGE + ".feature.", BASE_PACKAGE + ".app.")):
                errors.append(f"{source.relative_to(ROOT)}: shared package depends on {dependency}")
        if package.startswith(BASE_PACKAGE + ".feature.") and dependency.startswith(BASE_PACKAGE + ".app."):
            errors.append(f"{source.relative_to(ROOT)}: feature depends on application routing")
        if package.startswith(BASE_PACKAGE + ".ui.theme") and dependency.startswith(BASE_PACKAGE + ".ui.components"):
            errors.append(f"{source.relative_to(ROOT)}: theme depends on components")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)

print("Package paths and shared/feature/application dependency boundaries are valid.")
