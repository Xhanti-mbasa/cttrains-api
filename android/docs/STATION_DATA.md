# Station position provenance

The station selector names come from CT Trains. The 102 bundled station positions come from **Wikidata structured data (CC0)**, retrieved on 1 October 2026. This is geographic reference data, not a statement that a station is open, that a train is operating or that a plotted connection follows the track. Source entities are recorded in `station-provenance.json`.

Wikidata reference: https://www.wikidata.org/wiki/Wikidata:Licensing

The query was retrieved from https://query.wikidata.org/sparql with `format=json`:

```sparql
SELECT ?station ?stationLabel ?coord WHERE {
  SERVICE wikibase:box {
    ?station wdt:P625 ?coord.
    bd:serviceParam wikibase:cornerWest "Point(18.2 -34.4)"^^geo:wktLiteral;
                   wikibase:cornerEast "Point(19.3 -33.3)"^^geo:wktLiteral.
  }
  ?station wdt:P31/wdt:P279* wd:Q55488.
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
}
```

To refresh, save the JSON response locally, inspect it and run:

```bash
python3 android/scripts/import-station-positions.py /path/to/wikidata-response.json
```

The importer only maps source catalogue names, with explicit aliases for Akasia Park/Acacia Park and Dieprivier/Diep River. Unknown positions remain null. The map never geocodes station names silently or invents locations. New source stations remain searchable even before their position has been added. Selected routes show connections between consecutive known stops, broken across missing positions. They are not railway-track geometry or walking directions.

Google Maps street tiles are rendered by the supported Maps SDK after opt-in. Google geographic data is not scraped or bundled. Walking and alternative transit directions are available through an explicit external Google Maps handoff; Google results are never silently merged into CT Trains schedules.
