"""Mesh + VLM check: are the phones reachable, does a message cross the mesh,
does an image + brief go out, and is the command-centre link alive?

Run on the laptop, on the same Wi-Fi as the phones, with the VLM API switched on
in bitchat on each phone (About -> VLM API -> Enable, then force-stop and reopen).

    python tools/mesh_vlm_check.py --a 10.164.102.114
    python tools/mesh_vlm_check.py --a <phone A ip> --b <phone B ip>
    python tools/mesh_vlm_check.py --a <ip> --b <ip> --api https://disruptionops.onrender.com

What each check proves
  1 phone A answers         bitchat is running and its local API is on
  2 phone B answers         same for B
  3 A -> B over the mesh    a message sent from A is received by B (Bluetooth)
  4 B -> A over the mesh    and back
  5 image + brief from A    the path the camera uses (/send/image, /send/text)
  6 command-centre link     A's gateway settings: online, queue, last error
  7 API event router        the control room is listening (optional)
  8 VLM laptop              (--vlm <ip>) the teammate's VLM laptop answers, runs
                            the model on a test image and sends image + brief
                            through phone A into the mesh

Both phones must be on the laptop's Wi-Fi only so this script can reach their
APIs; the message itself travels phone to phone over the mesh. Check 9 (manual)
proves that by turning Wi-Fi and data off on phone B.
The VLM laptop runs `python run_vlm_to_bitchat.py`; it serves /health and
/analyze on port 8780 so this script (on any laptop on the same Wi-Fi) can use it.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import uuid

import requests

OK, BAD, WARN = "PASS", "FAIL", "WARN"
results: list[tuple[str, str, str]] = []


def say(name: str, verdict: str, detail: str = "") -> None:
    results.append((name, verdict, detail))
    print(f"[{verdict}] {name}" + (f"  - {detail}" if detail else ""))


def base(ip: str, port: int) -> str:
    return ip if ip.startswith("http") else f"http://{ip}:{port}"


def get(url: str, timeout: float = 5.0) -> dict:
    r = requests.get(url, timeout=timeout)
    r.raise_for_status()
    return r.json()


def status(name: str, url: str) -> dict | None:
    try:
        s = get(f"{url}/status")
    except Exception as e:  # noqa: BLE001
        say(f"{name} answers", BAD, f"{url}/status: {e}. Same Wi-Fi? VLM API enabled? App reopened?")
        return None
    if not s.get("api_enabled", False):
        say(f"{name} answers", BAD, "VLM API is disabled on this phone")
        return None
    peers = int(s.get("peers_count") or 0)
    detail = f"mesh_running={s.get('mesh_running')} peers={peers}"
    say(f"{name} answers", OK if peers > 0 else WARN,
        detail + ("" if peers > 0 else "  (no peers: Bluetooth + Location on? same app build on both?)"))
    return s


def latest_seq(url: str) -> int:
    return int(get(f"{url}/inbox?since=0").get("latest") or 0)


def send_text(url: str, text: str) -> bool:
    r = requests.post(f"{url}/send/text", json={"text": text}, timeout=8)
    if r.status_code != 200:
        print(f"      send/text -> HTTP {r.status_code}: {r.text[:200]}")
    return r.status_code == 200


def mesh_hop(name: str, src: str, dst: str, wait_s: float) -> None:
    nonce = uuid.uuid4().hex[:8]
    # An IDX1 heartbeat so the receiving phone keeps it in its /inbox.
    text = f"MESHTEST {nonce} IDX1|H|{{\"id\":\"t{nonce}\",\"n\":\"meshtest\"}}|-"
    try:
        since = latest_seq(dst)
    except Exception as e:  # noqa: BLE001
        say(name, BAD, f"receiver /inbox failed: {e} (old APK without /inbox?)")
        return
    if not send_text(src, text):
        say(name, BAD, "sender refused the message (rate limit 5 s, silence mode, or disabled)")
        return
    t0 = time.time()
    while time.time() - t0 < wait_s:
        try:
            items = get(f"{dst}/inbox?since={since}").get("messages") or []
        except Exception:  # noqa: BLE001
            items = []
        if any(nonce in (m.get("text") or "") for m in items):
            say(name, OK, f"received after {time.time() - t0:.1f} s")
            return
        time.sleep(1.0)
    say(name, BAD, f"not received within {wait_s:.0f} s (phones in range? both show 1+ peers?)")


def test_image() -> bytes:
    try:
        import cv2
        import numpy as np
        img = np.zeros((360, 640, 3), dtype=np.uint8)
        cv2.rectangle(img, (40, 40), (600, 320), (0, 90, 255), -1)
        cv2.putText(img, "MESH TEST " + time.strftime("%H:%M:%S"), (70, 190),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.4, (255, 255, 255), 3)
        ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 70])
        return buf.tobytes()
    except Exception:  # noqa: BLE001
        # 1x1 JPEG, enough to prove the path
        return bytes.fromhex(
            "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00fbd3ffd9")


def image_and_brief(url: str) -> None:
    try:
        r = requests.post(f"{url}/send/image",
                          files={"image": ("meshtest.jpg", io.BytesIO(test_image()), "image/jpeg")},
                          data={"caption": "MESH TEST image"}, timeout=15)
        ok_img = r.status_code == 200
        if not ok_img:
            print(f"      send/image -> HTTP {r.status_code}: {r.text[:200]}")
    except Exception as e:  # noqa: BLE001
        ok_img = False
        print(f"      send/image failed: {e}")
    time.sleep(6)  # the phone's API allows one send per ~5 s
    ok_txt = send_text(url, "MESH TEST brief: smoke rising from a parked car near the gate "
                            "geo:18.52040,73.85670")
    say("image + brief from A", OK if ok_img and ok_txt else BAD,
        "check the other phone: a test image, then a brief tagged Fire with a map pin")


def link(url: str) -> None:
    try:
        g = get(f"{url}/gateway")
    except Exception as e:  # noqa: BLE001
        say("command-centre link on A", WARN, f"/gateway: {e}")
        return
    d = (f"enabled={g.get('enabled')} online={g.get('online')} key_set={g.get('gateway_key_set')} "
         f"queued={g.get('queued')} last_error={g.get('last_error')}")
    good = g.get("enabled") and g.get("online") and not g.get("last_error")
    say("command-centre link on A", OK if good else WARN,
        d + ("" if good else "  (fine for a mesh-only test; set it in About -> Command Centre)"))


def api(url: str) -> None:
    try:
        s = get(f"{url.rstrip('/')}/api/v1/status/router", timeout=30)
    except Exception as e:  # noqa: BLE001
        say("API event router", WARN, f"{e} (Render may be asleep: open the API once and retry)")
        return
    say("API event router", OK if s.get("running") else BAD,
        f"running={s.get('running')} events={s.get('eventsSeen')} replans={s.get('replansRun')}")


def vlm(ip: str, phone_ip: str | None, port: int, use_camera: bool) -> None:
    url = ip if ip.startswith("http") else f"http://{ip}:8780"
    try:
        h = get(f"{url}/health", timeout=6)
    except Exception as e:  # noqa: BLE001
        say("VLM laptop answers", BAD, f"{url}/health: {e}. Is run_vlm_to_bitchat.py running there, "
            "same Wi-Fi, and Python allowed through its Windows firewall?")
        return
    say("VLM laptop answers", OK, f"{h.get('node')} model={h.get('model')} device={h.get('device')} "
        f"phone={h.get('phone')} reachable={h.get('phone_reachable')}")
    if phone_ip:
        if phone_ip.startswith("http"):          # --a given as a URL
            from urllib.parse import urlparse
            u = urlparse(phone_ip)
            phone_ip, port = u.hostname, u.port or port
        try:
            r = requests.post(f"{url}/phone", json={"ip": phone_ip, "port": port}, timeout=10).json()
            say("VLM laptop -> phone A", OK if r.get("phone_reachable") else BAD,
                f"now sending to {r.get('phone')} reachable={r.get('phone_reachable')}")
        except Exception as e:  # noqa: BLE001
            say("VLM laptop -> phone A", BAD, str(e))
            return
    print("      running the VLM (first run can take 30-60 s) ...")
    try:
        if use_camera:
            r = requests.post(f"{url}/analyze", timeout=240)
        else:
            r = requests.post(f"{url}/analyze", timeout=240,
                              files={"image": ("test.jpg", io.BytesIO(test_image()), "image/jpeg")})
        out = r.json()
    except Exception as e:  # noqa: BLE001
        say("VLM runs and sends", BAD, str(e))
        return
    if out.get("status") != "ok":
        say("VLM runs and sends", BAD, f"{out.get('status')}: {out.get('error')}")
        return
    say("VLM runs and sends", OK, f"{out.get('seconds')} s, sent to {out.get('sent_to')}: "
        f"\"{(out.get('scene') or '')[:120]}\"")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--a", required=True, help="phone A Wi-Fi IP (Bitchat -> About -> VLM API shows it)")
    ap.add_argument("--b", help="phone B Wi-Fi IP, to test delivery across the mesh")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--api", help="Indradhanu API base URL, to check the event router")
    ap.add_argument("--wait", type=float, default=25.0, help="seconds to wait for a mesh message")
    ap.add_argument("--skip-image", action="store_true")
    ap.add_argument("--vlm", help="teammate's VLM laptop IP (runs run_vlm_to_bitchat.py, port 8780)")
    ap.add_argument("--vlm-camera", action="store_true", help="analyse its live camera frame, not a test image")
    a = ap.parse_args()

    A = base(a.a, a.port)
    B = base(a.b, a.port) if a.b else None
    print(f"Phone A: {A}" + (f"   Phone B: {B}" if B else "   (no phone B: mesh delivery not tested)"))

    sa = status("phone A", A)
    sb = status("phone B", B) if B else None
    if sa and sb:
        mesh_hop("A -> B over the mesh", A, B, a.wait)
        time.sleep(6)
        mesh_hop("B -> A over the mesh", B, A, a.wait)
        time.sleep(6)
    if sa and not a.skip_image:
        image_and_brief(A)
    if sa:
        link(A)
    if a.api:
        api(a.api)
    if a.vlm:
        vlm(a.vlm, a.a if sa else None, a.port, a.vlm_camera)

    print("\nManual checks")
    print("  8 VLM brief on the phones: after check 8 the image and the model's description")
    print("    must appear on BOTH phones (A sends it, B receives it over the mesh).")
    print("  9 Offline: turn Wi-Fi and data OFF on phone B (Bluetooth on), then run with --a only;")
    print("    the test image and brief must still appear on phone B's screen.")

    bad = [r for r in results if r[1] == BAD]
    print(f"\n{len(results) - len(bad)}/{len(results)} checks passed or warned; {len(bad)} failed.")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
