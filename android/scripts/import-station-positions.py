"""Import a saved Wikidata SPARQL response; names must match the CT Trains catalogue.
Wikidata structured data is CC0. See docs/STATION_DATA.md for query and provenance.
"""
import json, pathlib, re, sys
root = pathlib.Path(__file__).resolve().parents[1]
rows = json.loads(pathlib.Path(sys.argv[1]).read_text())["results"]["bindings"]
key = lambda value: "".join(c for c in value.lower() if c.isalnum())
aliases = {"Akasia Park": "Acacia Park", "Dieprivier": "Diep River"}
lookup = {}
for row in rows:
    label = row["stationLabel"]["value"].replace("railway station", "").strip()
    match = re.fullmatch(r"Point\(([-.\d]+) ([-.\d]+)\)", row["coord"]["value"])
    if match:
        lookup[key(label)] = (float(match[2]), float(match[1]), row["station"]["value"])
records, provenance = [], []
for name in json.loads((root / "app/src/main/assets/stations.json").read_text())["stations"]:
    value = lookup.get(key(aliases.get(name, name)))
    records.append(dict(name=name, latitude=value[0] if value else None, longitude=value[1] if value else None))
    if value:
        provenance.append(dict(name=name, wikidata=value[2]))
(root / "app/src/main/assets/station-positions.json").write_text(json.dumps(records, indent=2) + "\n")
(root / "docs/station-provenance.json").parent.mkdir(exist_ok=True)
(root / "docs/station-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
print(f"{sum(r['latitude'] is not None for r in records)}/{len(records)} stations mapped")
