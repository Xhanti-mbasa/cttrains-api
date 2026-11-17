#!/usr/bin/env python3
"""Fail publication if production cannot update the existing production installations."""
import json, pathlib, sys
baseline=json.loads(pathlib.Path("release-compatibility.json").read_text())
manifest=json.loads(pathlib.Path(sys.argv[1]).read_text())
for key in ("packageName", "certificateSha256"):
    if manifest[key] != baseline[key]:
        raise SystemExit(f"Production {key} differs from the installed baseline; refuse publication.")
if manifest["versionCode"] <= baseline["minimumVersionCode"]:
    raise SystemExit("Production versionCode must increase above the installed baseline.")
if manifest["minSdk"] > baseline["maxMinSdk"]:
    raise SystemExit("Production update would exclude supported existing Android versions.")
print("Production package, signing certificate, version and Android compatibility passed.")
