#!/usr/bin/env python3
"""Validate the delivered SVG/Android pairs without a renderer or third-party packages."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
icons = root / 'design/icons'
rows = json.loads((icons / 'catalog.json').read_text())
android = '{http://schemas.android.com/apk/res/android}'
svg = '{http://www.w3.org/2000/svg}'
assert len(rows) == 18
assert len({row['name'] for row in rows}) == 18
for row in rows:
    name = row['name']
    master = ET.parse(icons / f'svg/{name}.svg').getroot()
    vector = ET.parse(root / f'app/src/main/res/drawable/{name}.xml').getroot()
    assert master.get('viewBox') == '0 0 24 24', name
    assert vector.get(android + 'viewportWidth') == vector.get(android + 'viewportHeight') == '24', name
    paths = master.findall(svg + 'path')
    vectors = vector.findall('path')
    assert [p.get('d') for p in paths] == row['paths'] == [p.get(android + 'pathData') for p in vectors], name
    assert all(node.tag in (svg + 'svg', svg + 'path') for node in master.iter()), name
    for path in paths:
        if row['filled']:
            assert name == 'ic_stop' and path.get('fill') == 'currentColor', name
        else:
            assert path.get('stroke') == 'currentColor' and path.get('stroke-width') == '1.75', name
            assert path.get('stroke-linecap') == path.get('stroke-linejoin') == 'round', name
print('18 matching SVG/VectorDrawable pairs: geometry, palette independence and vector-only content passed')
