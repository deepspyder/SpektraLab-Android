"""Record only build inputs/provenance; never credentials, signing files or user photos."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def repository(root: Path) -> Path:
    return next(candidate for candidate in (root, *root.parents) if (candidate / ".git").exists())


def git(root: Path, *args: str) -> bytes:
    root = repository(root)
    return subprocess.check_output(
        ["git", "-c", f"safe.directory={root.as_posix()}", "-C", str(root), *args]
    )


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def record(root: Path, scopes: list[str], output: Path, label: str) -> dict:
    files = {}
    for scope in scopes:
        target = root / scope
        candidates = target.rglob("*") if target.is_dir() else [target]
        for path in sorted(candidates):
            if path.is_file():
                files[path.relative_to(root).as_posix()] = digest(path)
    repo = repository(root)
    prefix = root.relative_to(repo)
    paths = [(prefix / scope).as_posix() for scope in scopes]
    patch = git(root, "diff", "HEAD", "--binary", "--", *paths)
    (output / f"{label}.patch").write_bytes(patch)
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z", "--", *paths)
    for name in untracked.decode().split("\0"):
        if not name:
            continue
        source = repo / name
        relative = source.relative_to(root)
        destination = output / f"{label}-untracked" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
    return {
        "head": git(root, "rev-parse", "HEAD").decode().strip(),
        "patch_sha256": hashlib.sha256(patch).hexdigest(),
        "files": files,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--libraw", type=Path, help="Optional actual LibRaw source directory")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    # Evidence stays local/ignored. Never replace an earlier checkpoint.
    if ROOT / ".tmp" not in output.parents:
        parser.error("output must be a new directory inside the Android repository's .tmp")
    output.mkdir(parents=True, exist_ok=False)
    report = {
        "app": record(ROOT, ["app/src/main", "app/build.gradle.kts", "build.gradle.kts",
                             "gradle.properties", "settings.gradle.kts", "tools"], output, "app"),
        "engine": record(args.engine.resolve(), ["src", "include", "resources", "resources_product"],
                         output, "engine"),
        "scope": "Build-input hashes and working-tree patches; no signing material or camera fixtures",
        "python": sys.version,
        "git_version": git(ROOT, "--version").decode().strip(),
    }
    if args.libraw:
        raw = args.libraw.resolve()
        if not (raw / "libraw/libraw.h").is_file():
            parser.error("libraw must contain libraw/libraw.h")
        report["libraw"] = {
            path.relative_to(raw).as_posix(): digest(path)
            for scope in ["src", "libraw", "internal"]
            for path in sorted((raw / scope).rglob("*")) if path.is_file()
        }
    (output / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Recorded {len(report['app']['files'])} app inputs and {len(report['engine']['files'])} engine inputs")


if __name__ == "__main__":
    main()
