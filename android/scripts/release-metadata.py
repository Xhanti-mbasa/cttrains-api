"""Validated release metadata shared by automatic, tag and manual workflow runs."""
import os
import pathlib
import re
import subprocess

version = dict(line.split("=", 1) for line in pathlib.Path("version.properties").read_text().splitlines() if "=" in line)
run_number = int(os.environ["GITHUB_RUN_NUMBER"])
requested = os.environ.get("REQUESTED_TAG", "").strip()
ref = os.environ.get("GITHUB_REF", "")
tag = ref.removeprefix("refs/tags/") if ref.startswith("refs/tags/") else requested
if not tag:
    tag = f"android-v{version['versionName']}-build.{run_number}"
match = re.fullmatch(r"android-v(\d+\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?)", tag)
if not match:
    raise SystemExit("Use a version tag such as android-v1.0.1 or android-v1.0.1-rc.1.")
code = int(version["versionCode"]) + run_number
if not 1 <= code <= 2_100_000_000:
    raise SystemExit("Android versionCode is outside its supported range.")
sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
with open(os.environ["GITHUB_OUTPUT"], "a") as output:
    for name, value in {"tag": tag, "version": match[1], "version_code": code, "source_sha": sha}.items():
        output.write(f"{name}={value}\n")
