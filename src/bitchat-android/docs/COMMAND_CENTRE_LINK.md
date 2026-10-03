# Command Centre Link

How this bitchat build talks to the command centre (DisruptionOps / Indradhanu API),
and what it keeps for offline use. Code: `vlm/IndradhanuGateway.kt` (the loop),
`util/SyncFlushWorker.kt` + `services/SyncBundleBuilder.kt` (the bundle),
`net/CivilizationSyncClient.kt` (HTTP), `ui/map/RouteCache.kt` + `ui/map/OfflineMapSheet.kt`
(navigation), `net/LastFix.kt` (last GPS fix).

## Setup

*About → Command Centre*:

- **Enable sync on reconnect**: on.
- **Command centre API**: the API base, e.g. `https://disruptionops.onrender.com`.
  A full URL (anything containing `/api/`) is used as-is for the bundle instead.
- **Gateway key**: the API's `MESH_GATEWAY_KEY` (sent as `X-Mesh-Gateway-Key`).
- **City id**: `pune` unless the API is set up for another city.
- **Listen to command centre**: on, to broadcast its alerts on the mesh.

Set the gateway phone's role to **Command** (About → Settings) so centres it gossips
show as verified on other phones.

The same settings can be sent to the local VLM API: `POST /gateway`
`{"enabled":true,"api_url":"...","gateway_key":"...","city_id":"pune","listen":true}`;
`GET /gateway` shows status.

## What happens while the phone has validated internet (every 10 s)

1. **Push, IDX1.** Packets heard on the mesh that contain `IDX1|R|`, `S`, `F`, `H`, `K`
   (from the Indradhanu web app in mesh mode, or a camera node) are queued on disk as
   they arrive, online or not, and posted to `/api/v1/mesh/inbound`.
2. **Push, bundle (every 30 s).** SOS messages, geotagged broadcasts and VLM briefs from
   the public timeline, plus shelters, are posted to `/api/v1/mesh/civ-sync` as
   `bitchat.civ.sync/v1`. A message without a `geo:` tag gets this phone's last fix.
   Ids the API accepted are remembered, so each incident goes once; the API also
   deduplicates. VLM briefs (`vlm-` ids) carry `source: vlm` and a hazard `kind`.
3. **Listen.** `/api/v1/mesh/outbox` is pulled; each alert, dispatch, cancellation or
   road block is broadcast publicly with category **Command**, a `geo:` tag before the
   IDX1 packet, shown on this phone's timeline as *Command Centre*, and acked. The chat
   hides the machine-readable IDX1 part; other gateways and the web app still read it.
4. **Cache (every 10 min).** `/api/v1/lifelines` and `/api/v1/shelters` become shelter
   registry entries (role Command, `cc-` ids, FULL when occupancy reaches capacity):
   persisted, shown on the map, the nearest 15 gossiped to the mesh so phones that never
   get signal see them too. Walking routes to the 3 nearest open centres are fetched
   (OSRM foot, falling back to driving) and saved.

## Offline navigation

*Map → Nearest centre* picks the nearest open centre (full/closed last). If a route was
saved from within 400 m of where you stand, it is drawn and labelled *cached route*;
if online, a route is fetched and saved; otherwise a straight line with a compass
direction. *Next* cycles through centres. Map tiles are whatever was viewed while online
(osmdroid cache, kept 30 days); OpenStreetMap's tile policy forbids bulk pre-download.

## Alert sorting

Every broadcast carries the sender's role (1-byte category). The chips under the header
filter the timeline: **All**, **Command**, **Ambulance**, **Fire**, **Gov**, **Civilian**.
With a chip selected only those categories show (All clears it). VLM briefs are tagged
from their words: fire/smoke → Fire, casualties → Ambulance, collapse/flood/violence →
Gov; `/send/text` also accepts `"category":"FIRE"` etc., and `lat`/`lon`.

## Mesh compatibility

All phones must run the same build. A role-tagged message sets a flag and prepends a
category byte, and both are covered by the packet signature; the Play Store bitchat or an
older build cannot verify or read it. Role `Command` (0x05) shows as untagged on builds
that predate it. Shelter records are accepted only from the phone that authored them, so
each phone gossips only its own (capped at 40 per new peer).
