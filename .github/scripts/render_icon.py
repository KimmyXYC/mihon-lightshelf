#!/usr/bin/env python3
"""Regenerate the repository PNG from the Android vector (requires CairoSVG)."""

from pathlib import Path
import xml.etree.ElementTree as ET

import cairosvg

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
vector = ET.parse(ROOT / "extension/src/main/res/drawable/ic_lightshelf.xml").getroot()
svg = ET.Element("svg", {
    "xmlns": "http://www.w3.org/2000/svg",
    "viewBox": f"0 0 {vector.attrib[ANDROID + 'viewportWidth']} {vector.attrib[ANDROID + 'viewportHeight']}",
    "width": "96", "height": "96",
})
for path in vector:
    if path.tag != "path" or set(path.attrib) != {ANDROID + "fillColor", ANDROID + "pathData"}:
        raise ValueError("Update this converter for new vector features")
    ET.SubElement(svg, "path", {"fill": path.attrib[ANDROID + "fillColor"], "d": path.attrib[ANDROID + "pathData"]})
destination = ROOT / ".github/assets/lightshelf.png"
destination.parent.mkdir(parents=True, exist_ok=True)
cairosvg.svg2png(bytestring=ET.tostring(svg), write_to=str(destination))
print(destination)
