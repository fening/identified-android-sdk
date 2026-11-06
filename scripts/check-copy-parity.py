#!/usr/bin/env python3
"""Fail when Android's consent/terms or other English copy drifts from iOS."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
ios = (root / "ios/Sources/Identified/Resources/en.lproj/Localizable.strings").read_text()
android = {node.attrib["name"]: node.text or "" for node in ET.parse(root / "android/identified-sdk/src/main/res/values/strings.xml").getroot()}
failures = []
count = 0
for key, raw in re.findall(r'^"([^"]+)"\s*=\s*("(?:[^"\\]|\\.)*");', ios, re.MULTILINE):
    expected = json.loads(raw)
    actual = android.get("identified_" + key.replace(".", "_"), "")
    if actual.startswith('"') and actual.endswith('"'):
        actual = actual[1:-1]
    actual = actual.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
    expected = re.sub(r"%(?:ld|d|@)", lambda match: "%s" if match.group() == "%@" else "%d", expected)
    actual = re.sub(r"%\d+\$", "%", actual)
    if actual != expected:
        failures.append(key)
    count += 1
if failures:
    raise SystemExit("Copy differs for: " + ", ".join(failures))
print(f"All {count} iOS/Android English strings match, including consent and terms.")
