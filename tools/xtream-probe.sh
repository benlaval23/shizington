#!/usr/bin/env bash
# Dumps real Xtream Codes API responses so models can be checked against actual JSON shapes.
# Reads xtream.server / xtream.backups / xtream.user / xtream.pass from local.properties (gitignored).
# Credentials are never printed; output goes to api-samples/ (also gitignored).
set -euo pipefail
cd "$(dirname "$0")/.."
prop() { grep "^$1=" local.properties | cut -d= -f2- || true; }
USER_=$(prop xtream.user); PASS_=$(prop xtream.pass)
[ -n "$USER_" ] && [ -n "$PASS_" ] || { echo "Add xtream.user and xtream.pass to local.properties"; exit 1; }

# Pick the first server that answers.
SERVER=""
for s in $(prop xtream.server) $(prop xtream.backups | tr ',' ' '); do
  if curl -fsS -m 10 -o /dev/null "$s/player_api.php?username=$USER_&password=$PASS_"; then SERVER=$s; break; fi
  echo "  $s did not answer, trying next"
done
[ -n "$SERVER" ] || { echo "No server reachable"; exit 1; }
echo "Using $SERVER"

OUT=api-samples; mkdir -p "$OUT"
get() { curl -fsS -m 60 "$SERVER/player_api.php?username=$USER_&password=$PASS_&$1"; }
redact() { sed -E 's/"(username|password)":"[^"]*"/"\1":"***"/g'; }

get "" | redact > "$OUT/auth.json"
for a in get_live_categories get_vod_categories get_series_categories get_live_streams get_vod_streams get_series; do
  get "action=$a" > "$OUT/$a.json"; echo "  $a: $(wc -c < "$OUT/$a.json") bytes"
done
# One detail sample of each kind, using the first id in each list.
first() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d[0][sys.argv[2]] if isinstance(d,list) and d else '')" "$1" "$2"; }
VOD=$(first "$OUT/get_vod_streams.json" stream_id); SER=$(first "$OUT/get_series.json" series_id); LIVE=$(first "$OUT/get_live_streams.json" stream_id)
[ -n "$VOD" ] && get "action=get_vod_info&vod_id=$VOD" > "$OUT/get_vod_info.json"
[ -n "$SER" ] && get "action=get_series_info&series_id=$SER" > "$OUT/get_series_info.json"
[ -n "$LIVE" ] && get "action=get_short_epg&stream_id=$LIVE&limit=4" > "$OUT/get_short_epg.json"
echo "Done. Samples are in $OUT/ (gitignored). They contain stream URLs/ids but no credentials."
