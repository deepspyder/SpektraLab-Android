"""Verify the distributable APK, including the vendor installer's JAR certificate path."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from zipfile import ZipFile

RELEASE_CERTIFICATE = "3df31b6b60a0e7edb705d495c086e28cb699883d1b29ceb723992b08ed43af39"
ROOT = Path(__file__).resolve().parents[1]


def sdk_root(explicit):
    if explicit:
        return Path(explicit)
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            return Path(os.environ[key])
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                value = line.split("=", 1)[1].replace(chr(92) + ":", ":").replace(chr(92) * 2, chr(92))
                return Path(value)
    raise ValueError("Set ANDROID_SDK_ROOT or pass --sdk-root")


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise ValueError(result.stdout + result.stderr)
    return result.stdout


def verify(apk, sdk, certificate):
    candidates = [path for path in (sdk / "build-tools").iterdir() if (path / "lib/apksigner.jar").is_file()]
    if not candidates:
        raise ValueError("Android build-tools/apksigner.jar not found")
    tools = max(candidates, key=lambda path: tuple(map(int, re.findall("[0-9]+", path.name))))
    java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe" if os.name == "nt" and os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java:
        raise ValueError("Java is required to run Android apksigner")
    signer = [str(java), "-Xmx256m", "-jar", str(tools / "lib/apksigner.jar"), "verify", "--verbose"]
    modern = run(signer + ["--print-certs", str(apk)])
    legacy = run(signer + ["--min-sdk-version", "21", "--max-sdk-version", "23", str(apk)])
    # minSdk 26 normally verifies V2/V3 and can report V1=false even when the JAR
    # certificate is present. Force the legacy verifier separately to test that path.
    expected = {"v1": (legacy, "Verified using v1 scheme (JAR signing): true"),
                "v2": (modern, "Verified using v2 scheme (APK Signature Scheme v2): true"),
                "v3": (modern, "Verified using v3 scheme (APK Signature Scheme v3): true")}
    for scheme, (output, marker) in expected.items():
        if marker not in output:
            raise ValueError("Release compatibility gate failed: missing verified " + scheme + " signature")
    match = re.search("Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", modern)
    if not match or match.group(1).lower() != certificate.lower():
        raise ValueError("Release certificate differs from the expected update certificate")
    with ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK contains a corrupt ZIP entry")
        certificates = [name for name in archive.namelist() if name.startswith("META-INF/") and
                        name.upper().endswith((".RSA", ".EC", ".DSA"))]
        if not certificates or not any(name.upper().endswith(".SF") for name in archive.namelist()):
            raise ValueError("JAR signature files are missing")
    aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    metadata = run([str(aapt), "dump", "badging", str(apk)])
    package = re.search("package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", metadata)
    if not package or package.group(1) != "com.spektralab.android":
        raise ValueError("Unexpected release application ID")
    return {"apk": str(apk), "bytes": apk.stat().st_size, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "signatures": {key: True for key in expected}, "certificate_sha256": match.group(1),
            "jar_certificates": certificates, "application_id": package.group(1),
            "version_code": int(package.group(2)), "version_name": package.group(3)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk-root")
    parser.add_argument("--certificate", default=RELEASE_CERTIFICATE)
    parser.add_argument("--json", type=Path, help="Save verification evidence next to the delivered APK")
    args = parser.parse_args()
    try:
        report = verify(args.apk.resolve(strict=True), sdk_root(args.sdk_root), args.certificate)
    except (ValueError, OSError) as error:
        parser.exit(1, "Release verification failed: " + str(error) + chr(10))
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
