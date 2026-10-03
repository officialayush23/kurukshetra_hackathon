"""Send hazard detections to Indradhanu, over the internet or over the mesh.

This node is the "Sentinel" in the Indradhanu architecture: it sees, it
confirms, and it reports. It never decides what to dispatch.

Two paths, tried in order for every confirmed detection (`prefer: mesh`, the
default, puts the phone first):

  1. MESH   POST http://<phone>:8765/send/text       an IDX1 "S" packet into the
            bitchat mesh, which any phone with signal forwards to the API
  2. HTTPS  POST <api>/api/v1/ingest/sensor         only if the phone cannot be
            reached and this laptop has an API configured

The phone is the primary link: the camera never needs the command centre to be
reachable. `api` and `gateway_key` may be left empty.

Both carry the same event id, so a detection that arrives both ways is one
report, not two (the API deduplicates on it).

What is sent, and what is not
-----------------------------
Only hazards: fire, smoke, fall, fight/violence, gathering, object left. Never
identities, faces, phone use or smoking. In `disaster_mode` those detectors are
switched off entirely (see `apply_disaster_mode`), because identifying people
from public cameras is not needed to save anyone and is a liability.

A detection is sent when it is confident enough and has not been sent for this
camera and kind within `cooldown_s`, so a fire burning for ten minutes is one
report plus corroboration, not six hundred.

Configure in configs/pipeline.yaml:

    indradhanu:
      enabled: true
      api: "https://your-api.onrender.com"
      gateway_key: ""        # MESH_GATEWAY_KEY on the API
      hmac_key: ""           # MESH_HMAC_KEY on the API
      node_id: "cam_01"
      lat: 18.5204           # where this camera is: detections have no GPS
      lon: 73.8567
      min_confidence: 0.55
      cooldown_s: 120
      mesh: true             # send through the bitchat phone
      prefer: mesh           # mesh (phone first) | https (internet first)
      disaster_mode: true
"""
from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
import uuid
from typing import Any

import requests

#: Detector event names -> the kind the API maps to an incident category.
HAZARD_KIND = {
    "FIRE": "fire", "SMOKE": "smoke", "FALL": "fall", "FIGHT": "fight",
    "VIOLENCE": "violence", "GATHERING": "gathering", "OBJECT_LEFT": "object_left",
}
#: Never sent, whatever the config says.
NEVER = {"PHONE", "SMOKING", "IDENTITY", "FACE", "REID"}
#: Hazard words in a VLM scene description -> detector kind. Same list as the
#: phone's SyncBundleBuilder.hazardKind, so both ends agree.
SCENE_KINDS = (
    (("fire", "flame", "burning", "blaze"), "fire"),
    (("smoke",), "smoke"),
    (("flood", "waterlog", "submerged", "inundat"), "flood"),
    (("collapse", "rubble", "debris"), "collapse"),
    (("injur", "unconscious", "bleeding", "casualt", "lying on the ground"), "medical"),
    (("assault", "fight", "violence", "attack"), "fight"),
)


def scene_kind(text: str) -> str | None:
    t = (text or "").lower()
    for words, kind in SCENE_KINDS:
        if any(w in t for w in words):
            return kind
    return None


#: Switched off in disaster mode.
DISASTER_MODE_OFF = ("face", "reid", "identity_fusion", "phone", "smoking", "par")


def apply_disaster_mode(cfg: dict) -> list[str]:
    """Turn identity-related detectors off. Returns what was switched off."""
    ind = cfg.get("indradhanu") or {}
    if not ind.get("disaster_mode", False):
        return []
    feats = cfg.setdefault("features", {})
    off = [k for k in DISASTER_MODE_OFF if feats.get(k)]
    for k in DISASTER_MODE_OFF:
        feats[k] = False
    return off


def _sign(key: str, type_: str, payload: str) -> str:
    return hmac.new(key.encode(), f"IDX1|{type_}|{payload}".encode(),
                    hashlib.sha256).hexdigest()[:16]


def packet(body: dict, key: str, human: str = "") -> str:
    """IDX1 'S' packet. Same format as the API's app/mesh/envelope.py."""
    clean = {k: (round(v, 5) if k in ("la", "lo") else v)
             for k, v in body.items() if v not in (None, "")}
    payload = json.dumps(clean, separators=(",", ":"), ensure_ascii=False)
    sig = _sign(key, "S", payload) if key else "-"
    text = f"IDX1|S|{payload}|{sig}"
    return f"{human} {text}" if human else text


class IndradhanuBridge:
    def __init__(self, cfg: dict, bitchat_ip: str | None = None, bitchat_port: int = 8765,
                 phone_url=None):
        c = cfg or {}
        self.enabled = bool(c.get("enabled", False))
        self.prefer = str(c.get("prefer") or "mesh").lower()
        # A callable returning the phone's current base URL (the bitchat client
        # re-targets itself when it auto-discovers the phone), or a fixed one.
        self._phone_url = phone_url
        self.api = (c.get("api") or "").rstrip("/")
        self.gateway_key = c.get("gateway_key") or ""
        self.hmac_key = c.get("hmac_key") or ""
        self.node = c.get("node_id") or "cam_01"
        self.lat = float(c.get("lat") or 0.0)
        self.lon = float(c.get("lon") or 0.0)
        self.min_conf = float(c.get("min_confidence", 0.55))
        self.cooldown = float(c.get("cooldown_s", 120))
        self.use_mesh = bool(c.get("mesh", True))
        self._phone_fixed = (f"http://{bitchat_ip}:{bitchat_port}"
                             if bitchat_ip and bitchat_ip != "auto" else None)
        self._last: dict[str, float] = {}
        self._q: list[dict] = []
        self._lock = threading.Lock()
        self._mesh_last = 0.0
        self.sent_https = 0
        self.sent_mesh = 0
        if self.enabled:
            threading.Thread(target=self._worker, daemon=True, name="indradhanu").start()
            print(f"[indradhanu] bridge on  node={self.node} prefer={self.prefer} "
                  f"phone={self.phone or 'auto'} api={self.api or '-'} "
                  f"signed={'yes' if self.hmac_key else 'NO'}")

    @property
    def phone(self) -> str | None:
        if self._phone_url is not None:
            try:
                url = self._phone_url()
                if url and "://auto" not in url:
                    return url.rstrip("/")
            except Exception:  # noqa: BLE001
                pass
        return self._phone_fixed

    def offer_scene(self, scene: str, confidence: float = 0.7) -> bool:
        """A VLM scene description. Queued as a hazard only when it names one."""
        if not self.enabled:
            return False
        kind = scene_kind(scene)
        if kind is None:
            return False
        now = time.time()
        if now - self._last.get("scene:" + kind, 0.0) < self.cooldown:
            return False
        self._last["scene:" + kind] = now
        body = {
            "id": f"{self.node}-vlm-{kind}-{uuid.uuid4().hex[:8]}",
            "n": self.node, "k": kind, "c": round(float(confidence), 3),
            "la": self.lat, "lo": self.lon, "f": "vlm", "v": 1,
            "x": (scene or "")[:160], "t": int(now),
        }
        with self._lock:
            self._q.append(body)
        print(f"[indradhanu] VLM scene -> {kind} queued for the phone")
        return True

    # -------------------------------------------------------------- intake --
    def offer(self, ev: Any, vlm_agreed: bool = False, caption: str | None = None) -> bool:
        """Consider one detector event. Returns True if it was queued to send."""
        if not self.enabled:
            return False
        et = str(getattr(ev, "event_type", "") or "").upper()
        if et in NEVER or et not in HAZARD_KIND:
            return False
        details = getattr(ev, "details", {}) or {}
        conf = getattr(ev, "confidence", None)
        if callable(conf):
            conf = conf()
        conf = float(conf if conf is not None else details.get("confidence", 0.6))
        if conf < self.min_conf:
            return False
        kind = HAZARD_KIND[et]
        now = time.time()
        if now - self._last.get(kind, 0.0) < self.cooldown:
            return False
        self._last[kind] = now
        body = {
            "id": f"{self.node}-{kind}-{uuid.uuid4().hex[:8]}",
            "n": self.node, "k": kind, "c": round(conf, 3),
            "la": self.lat, "lo": self.lon,
            "f": details.get("frames") or details.get("confirm") or None,
            "v": 1 if vlm_agreed else None,
            "x": (caption or "")[:160] or None,
            "t": int(now),
        }
        with self._lock:
            self._q.append(body)
        return True

    # -------------------------------------------------------------- sender --
    def _worker(self) -> None:
        while True:
            item = None
            with self._lock:
                if self._q:
                    item = self._q.pop(0)
            if item is None:
                time.sleep(0.2)
                continue
            order = ("mesh", "https") if self.prefer != "https" else ("https", "mesh")
            done = False
            for path in order:
                if path == "mesh" and self.use_mesh and self.phone and self._mesh(item):
                    self.sent_mesh += 1
                    done = True
                    break
                if path == "https" and self._https(item):
                    self.sent_https += 1
                    done = True
                    break
            if done:
                continue
            # Neither path worked: keep it, try again shortly, drop after 30 min.
            if time.time() - item["t"] < 1800:
                with self._lock:
                    self._q.append(item)
            time.sleep(5)

    def _https(self, b: dict) -> bool:
        if not self.api or not self.gateway_key:
            return False
        try:
            r = requests.post(
                f"{self.api}/api/v1/ingest/sensor",
                headers={"X-Mesh-Gateway-Key": self.gateway_key},
                json={"nodeId": b["n"], "eventId": b["id"], "kind": b["k"],
                      "confidence": b["c"], "lat": b["la"], "lon": b["lo"],
                      "frames": b.get("f"), "vlmAgreed": bool(b.get("v")),
                      "caption": b.get("x"), "occurredAt": b["t"]},
                timeout=6,
            )
            ok = r.status_code == 200
            print(f"[indradhanu] https {r.status_code} {b['k']} -> "
                  f"{(r.json() or {}).get('outcome') if ok else r.text[:120]}")
            return ok
        except Exception as e:  # noqa: BLE001 - offline is expected
            print(f"[indradhanu] https unavailable ({type(e).__name__})")
            return False

    def _mesh(self, b: dict) -> bool:
        wait = 5.5 - (time.monotonic() - self._mesh_last)
        if wait > 0:
            time.sleep(wait)
        text = packet(b, self.hmac_key,
                      human=f"[{b['k'].upper()}] camera {b['n']} {int(b['c'] * 100)}%")
        try:
            r = requests.post(f"{self.phone}/send/text", json={"text": text}, timeout=6)
            self._mesh_last = time.monotonic()
            ok = r.status_code == 200
            print(f"[indradhanu] mesh {r.status_code} {b['k']}"
                  + ("" if ok else f" ({r.text[:80]})"))
            return ok
        except Exception as e:  # noqa: BLE001
            print(f"[indradhanu] mesh unavailable ({type(e).__name__})")
            return False


def build_bridge(cfg: dict, bitchat_client: Any = None) -> IndradhanuBridge | None:
    """Pass the bitchat client so the bridge follows the phone it found."""
    ind = cfg.get("indradhanu") or {}
    if not ind.get("enabled"):
        return None
    bc = cfg.get("bitchat") or {}
    fn = (lambda: getattr(bitchat_client, "base_url", None)) if bitchat_client is not None else None
    return IndradhanuBridge(ind, bitchat_ip=bc.get("ip"), bitchat_port=int(bc.get("port", 8765)),
                            phone_url=fn)
