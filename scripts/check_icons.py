#!/usr/bin/env python3
"""Validate original SVG/Android pairs, including optical 16dp variants."""
from pathlib import Path
import json
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
icons = root / 'design/icons'
rows = json.loads((icons / 'catalog.json').read_text())
supporting = json.loads((icons / 'supporting.json').read_text())
android = '{http://schemas.android.com/apk/res/android}'
svg = '{http://www.w3.org/2000/svg}'
assert len(rows) == 18
assert len({row['name'] for row in rows}) == 18
count = 0
for row in rows + supporting:
    for compact in ([False, True] if 'small_paths' in row else [False]):
        name = row['name'] + ('_16' if compact else '')
        master = ET.parse(icons / f'svg/{name}.svg').getroot()
        vector = ET.parse(root / f'app/src/main/res/drawable/{name}.xml').getroot()
        assert master.get('viewBox') == '0 0 24 24', name
        assert vector.get(android + 'viewportWidth') == vector.get(android + 'viewportHeight') == '24', name
        assert vector.get(android + 'width') == vector.get(android + 'height') == ('16dp' if compact else '24dp'), name
        paths = master.findall(svg + 'path')
        vectors = vector.findall('path')
        expected = row['small_paths'] if compact else row['paths']
        stroke = '2.25' if compact else '1.75'
        assert [p.get('d') for p in paths] == expected == [p.get(android + 'pathData') for p in vectors], name
        assert all(node.tag in (svg + 'svg', svg + 'path') for node in master.iter()), name
        for path, drawable in zip(paths, vectors):
            if row['filled']:
                assert row['name'] == 'ic_stop' and path.get('fill') == 'currentColor', name
            else:
                assert path.get('stroke') == 'currentColor' and path.get('stroke-width') == stroke, name
                assert path.get('stroke-linecap') == path.get('stroke-linejoin') == 'round', name
                assert drawable.get(android + 'strokeWidth') == stroke, name
                assert drawable.get(android + 'strokeLineCap') == drawable.get(android + 'strokeLineJoin') == 'round', name
        count += 1
print(f'{count} SVG/VectorDrawable pairs passed: 18 masters, 18 optical 16dp variants, 1 existing message marker')
