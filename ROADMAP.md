# BiChat × Indradhanu — progress and roadmap

Shared between two repositories; the same file lives in both:

- **kurukshetra_hackathon** — `src/bitchat-android` (BiChat, the Android mesh app) and
  `src/ai-surveillance` (camera + VLM pipeline).
- **DisruptionOps** — `backend` (command centre API) and `frontend/indradhanu`
  (admin dashboard, citizen PWA `/citizen`, crew PWA `/field`).

Update this file in **both** repos whenever an item moves.

## The product, in one paragraph

One phone app for citizens and field crews. **With internet** it is the full Indradhanu
experience, connected to the command centre: live map, guidance routes, dispatches,
reports with photos and voice. **Without internet** the same app falls back to BiChat:
Bluetooth/Wi-Fi mesh chat, SOS, the offline map with saved routes, and reports and
status updates that travel phone to phone until one has signal. Switching between them
keeps the person's place: signed-in identity, current route, selected incident, unsent
drafts. VLM camera alerts are for government only and never reach citizens.

---

## Done

| Date | Repo | What |
|---|---|---|
| 2026-10-03 | kurukshetra | Full-screen emergency map in BiChat (`ui/map/EmergencyMap*.kt`): dark map, clustering, SOS pulse, draggable sheet, filters, "View on map" from chat, open chat from map. |
| 2026-10-03 | kurukshetra | Map navigation uses `RouteCache` saved routes (green, works offline), fetches when online, honest dashed direct line otherwise. |
| 2026-10-03 | kurukshetra | Build fixes after merge: duplicate `parseGeoTag`, `Role.COMMAND` in map `when`s; Android Studio sync past dependency verification (trust `-sources`/`-javadoc` only). |
| 2026-10-03 | kurukshetra | Removed `_claude_tmp/` from the repo and ignored it. |
| 2026-10-03 | DisruptionOps | Citizen and crew PWAs map-first: full-screen map, bottom sheet (phone), floating card (tablet), map + panel (desktop), nav panel, filters, connectivity pill, Mapbox Standard night basemap, badge markers shared with Android. |
| 2026-10-03 | DisruptionOps | Navigation from the command centre is visible: citizen gets `/citizen/guide` routes (auto-started by an advisory with a safe location); crews get their unit's dispatched route, steps and ETA from `/field/state`, with hazards ahead and re-route notices. |
| 2026-10-03 | both | VLM/camera alerts are government-only (item 1). |
| earlier | kurukshetra | `IndradhanuGateway`: phone ↔ command centre bridge (push mesh SOS/reports, pull outbox alerts/dispatches/road blocks onto the mesh as role COMMAND, cache centres + routes). See `src/bitchat-android/docs/COMMAND_CENTRE_LINK.md`. |

Verified only in this order: PWA typecheck/build/screenshots; Android map code compiled
against a stand-in classpath. **The Android app has not yet been built on a device** —
do that first on the next session (`cd src/bitchat-android && ./gradlew :app:assembleDebug`).

---

## To do (in order)

### 1. VLM alerts → government only  ·  status: done (verify on devices)

- [x] Android: every broadcast VLM brief carries an IDX1 `S` packet
      (`VlmMessageHandler.asSensorPacket`, unsigned `-`, kind from the text, last GPS fix).
      It still travels the mesh so any gateway phone forwards it to the command centre.
- [x] Android: `services/AudiencePolicy.kt` hides `S` packets and `vlm-` messages from the
      public timeline, channels, map and haptics unless the local role is GOV or COMMAND.
- [x] Backend: sensor reports are keyed `device:sensor:<node>`; `/citizen/state` and
      `/field/state` drop incidents only cameras reported (`CAMERA_ONLY` in
      `api/v1/personas.py`) until an operator moves them past `reported` or a person
      reports the same thing. The dashboard is unchanged and sees everything.
      `/citizen/guide` still routes around them (safety, not display).
- [x] PWA: no change needed; both apps read incidents only from those two endpoints.
- [ ] On devices: a Civilian phone next to a Gov phone; send a VLM brief; only the Gov
      phone shows it, and it appears on the dashboard.

### 2. Sign-in in BiChat (citizen and field crew)

- [ ] Android login screen with the **same accounts** as the PWA (Supabase auth;
      `/auth/me` gives the role). Citizen may continue without an account.
- [ ] Signed-in role maps to the mesh role: field operator → their service
      (Ambulance/Fire/Gov), staff → Gov/Command, citizen → Civilian.
- [ ] Session persists offline (last verified identity, clearly marked as offline).

### 3. One app: online = Indradhanu, offline = BiChat

- [ ] Android hosts the PWA (`/citizen` or `/field` by role) in a WebView when the
      command centre is reachable; the native BiChat UI when not. Automatic switch, plus
      a manual "Use mesh" / "Back online" button.
- [ ] Shared session: the WebView is signed in with the Android session token.
- [ ] State handoff both ways through a JS bridge: current route/destination, selected
      incident, report draft (text + photo), queued reports, last position.
- [ ] Reports queued in the PWA outbox while switching are not lost or duplicated.

### 4. Offline parity (what works online works on the mesh, to some extent)

| Online (PWA) | Offline (BiChat) | Status |
|---|---|---|
| See incidents, alerts, road blocks | Command broadcasts via gateway, geo-tagged messages, registry | partly done; road blocks are not drawn as closures on the native map yet |
| Guidance to shelter/hospital/food/water | Saved routes to nearest centres (`RouteCache`) | done for centres the gateway cached |
| Report a hazard (text/photo/voice) | IDX1 `R` report over mesh (text + GPS) | to do in native UI |
| Crew status (arrived, puncture, full…) | IDX1 `F` packet over mesh | to do |
| Crew dispatch + route | Dispatch broadcast `D` + saved route | dispatch shows as a message; route to do |
| SOS | SOS broadcast + map | done |

### 5. Apple-style redesign of all of BiChat

Design direction (from the ui-ux-pro-max review): calm, trustworthy, system-like;
no emoji icons; no purple/pink AI gradients; red reserved for emergencies.

- [ ] Design tokens: SF-like type scale (Inter/system), 8-pt spacing, continuous
      corner radii, grouped inset lists, translucent bars, spring animations,
      light and dark themes with 4.5:1 contrast, 44-pt touch targets.
- [ ] Screens: onboarding/permissions, sign-in, chat list + chat, channels, people
      (mesh peers), SOS composer, shelters/centres, settings (grouped), command-centre
      link settings, map (already redone; align tokens).
- [ ] Replace emoji glyphs used as icons (roles, shelters, statuses) with vector icons.

### 6. Command-centre navigation visibility (check list)

- [x] Crew PWA shows its unit's dispatched route.
- [x] Citizen PWA shows the guide route and auto-routes on an advisory.
- [ ] BiChat native map draws command-centre road blocks (`B` packets) as closures and
      uses dispatch `D` packets as the crew's guidance target.

---

## Known constraints

- The Android SDK cannot be downloaded in the cloud coding environment, so Android
  changes are compile-checked against stand-ins only. Build on a real machine.
- Mapbox and OSM tile hosts are not reachable from that environment either; PWA map
  screenshots there use a plain dark stand-in basemap.
- All phones must run the same BiChat build (role-tagged packets).
