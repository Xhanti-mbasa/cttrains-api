#!/usr/bin/env python3
"""Import an authorized OSM Overpass JSON snapshot; no runtime routing API/key needed.

Fetch the documented bounded query yourself, then pass its JSON file as the argument.
Station anchors use the nearest mapped rail node within 400 m. Crossings are joined
only when OSM ways share the same node ID; proximity does not invent a junction.
"""
import json
import math
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1]
source = pathlib.Path(sys.argv[1])
data = json.loads(source.read_text())
if data.get('remark'):
    raise SystemExit('Overpass returned an incomplete result: ' + data['remark'])
nodes, pairs = {}, set()
for way in data['elements']:
    if way.get('type') != 'way' or way.get('tags', {}).get('railway') != 'rail':
        continue
    if len(way.get('nodes', [])) != len(way.get('geometry', [])):
        raise SystemExit('Missing way geometry')
    for key, point in zip(way['nodes'], way['geometry']):
        nodes[key] = [round(point['lon'], 7), round(point['lat'], 7)]
    pairs.update(tuple(sorted((a, b))) for a, b in zip(way['nodes'], way['nodes'][1:]) if a != b)
keys = sorted(nodes)
indices = {key: i for i, key in enumerate(keys)}
coords = [nodes[key] for key in keys]
def meters(a, b):
    lat = math.radians(b[1] - a[1]); lon = math.radians(b[0] - a[0])
    h = math.sin(lat / 2)**2 + math.cos(math.radians(a[1])) * math.cos(math.radians(b[1])) * math.sin(lon / 2)**2
    return 6371000 * 2 * math.asin(math.sqrt(min(1, h)))
anchors, alternatives, missing, errors = {}, {}, [], []
for station in json.loads((root/'app/src/main/assets/station-positions.json').read_text()):
    target = [station['longitude'], station['latitude']]
    nearby = sorted((meters(target, point), i) for i, point in enumerate(coords))
    gap, index = nearby[0]
    if gap <= 400:
        key = ''.join(c for c in station['name'].lower() if c.isalnum())
        anchors[key] = index
        alternatives[key] = [i for gap, i in nearby if gap <= 250][:24] or [index]
        errors.append(gap)
    else:
        missing.append({'station': station['name'], 'nearestRailMeters': round(gap)})
snapshot = data['osm3s']['timestamp_osm_base']
asset = {'snapshotAt': snapshot, 'nodes': coords, 'edges': [[indices[a], indices[b]] for a, b in sorted(pairs)], 'anchors': anchors, 'alternatives': alternatives}
output = root/'app/src/main/assets/rail-network.json'
output.write_text(json.dumps(asset, separators=(',', ':')) + '\n')
provenance = {'source': 'OpenStreetMap via Overpass API', 'snapshotAt': snapshot,
    'endpoint': 'https://overpass-api.de/api/interpreter',
    'query': '[out:json][timeout:45];way["railway"="rail"]["service"!~"yard|siding|spur"]["usage"!="industrial"](-34.24,18.38,-33.59,19.06);out body geom;',
    'licence': 'ODbL 1.0', 'copyright': '© OpenStreetMap contributors', 'licenceUrl': 'https://opendatacommons.org/licenses/odbl/1-0/',
    'nodes': len(coords), 'edges': len(pairs), 'anchoredStations': len(anchors),
    'maxAnchorDistanceMeters': round(max(errors)), 'unanchoredStations': missing, 'candidateRadiusMeters': 250, 'maxCandidatesPerStation': 24,
    'limitations': 'Undirected mapped rail geometry; not official train path, live availability, signalling, platform walking routes or a platform assignment. Gaps remain dashed.'}
(root/'docs/rail-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print(json.dumps({'bytes': output.stat().st_size, 'nodes': len(coords), 'edges': len(pairs), 'anchoredStations': len(anchors), 'missing': missing}))
