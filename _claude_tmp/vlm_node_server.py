"""VLM node: lets any machine on the same network reach the VLM laptop by IP.

The VLM runs on one laptop (the one with the GPU). This small HTTP server runs
next to it, so the control laptop, a test script, or the Indradhanu API on the
LAN can check it and use it without touching that laptop:

    GET  http://<vlm-laptop-ip>:8780/health    model, device, busy, last scene,
                                                which phone it sends to, reachable?
    POST http://<vlm-laptop-ip>:8780/analyze   run the VLM now on an uploaded
                                                image (multipart "image", or JSON
                                                {"image_base64": ...}) or, with no
                                                image, on the current camera frame;
                                                sends image + brief to the phone and
                                                returns the brief
    POST http://<vlm-laptop-ip>:8780/phone     {"ip": "<phone ip>", "port": 8765}
                                                send to another phone from now on

No auth: it is meant for a closed demo network. Do not port-forward it.
Windows asks once to allow Python on private networks: allow it, or nothing
on the network can reach port 8780.
"""
from __future__ import annotations

import base64
import json
from email import policy
from email.parser import BytesParser
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable

import cv2
import numpy as np
import requests


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "127.0.0.1"


class VLMNode:
    def __init__(
        self,
        analyze: Callable[[np.ndarray], tuple[str, str]],
        latest_frame: Callable[[], np.ndarray | None],
        bitchat_client: Any | None,
        info: dict | None = None,
        port: int = 8780,
    ):
        self.analyze = analyze
        self.latest_frame = latest_frame
        self.client = bitchat_client
        self.info = info or {}
        self.port = port
        self._busy = threading.Lock()
        self.last_scene = ""
        self.last_at = 0.0
        self.runs = 0

    # -------------------------------------------------------------- actions --
    def phone_url(self) -> str | None:
        return getattr(self.client, "base_url", None)

    def phone_ok(self) -> bool:
        url = self.phone_url()
        if not url:
            return False
        try:
            return bool(requests.get(f"{url}/status", timeout=3).json().get("api_enabled"))
        except Exception:  # noqa: BLE001
            return False

    def health(self) -> dict:
        return {
            "status": "ok", "node": socket.gethostname(), "ip": local_ip(), "port": self.port,
            "busy": self._busy.locked(), "runs": self.runs,
            "last_scene": self.last_scene,
            "seconds_since_last": round(time.time() - self.last_at, 1) if self.last_at else None,
            "phone": self.phone_url(), "phone_reachable": self.phone_ok(),
            **self.info,
        }

    def run(self, frame: np.ndarray | None, send: bool = True) -> dict:
        if frame is None:
            frame = self.latest_frame()
        if frame is None:
            return {"status": "error", "error": "no image given and no camera frame yet"}
        if not self._busy.acquire(blocking=False):
            return {"status": "busy", "error": "the VLM is already running; try again shortly"}
        try:
            t0 = time.perf_counter()
            scene, entities = self.analyze(frame)
            self.last_scene, self.last_at, self.runs = scene, time.time(), self.runs + 1
            sent = False
            if send and self.client is not None:
                self.client.send_scene(scene, frame)
                sent = True
            return {"status": "ok", "scene": scene, "entities": entities,
                    "seconds": round(time.perf_counter() - t0, 1),
                    "sent_to": self.phone_url() if sent else None}
        finally:
            self._busy.release()

    def set_phone(self, ip: str, port: int = 8765) -> dict:
        if self.client is None:
            return {"status": "error", "error": "bitchat is disabled in pipeline.yaml"}
        self.client.set_ip(ip, port)
        return {"status": "ok", "phone": self.phone_url(), "phone_reachable": self.phone_ok()}

    # --------------------------------------------------------------- server --
    def start(self) -> "VLMNode":
        node = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):  # quiet
                pass

            def _send(self, code: int, obj: dict) -> None:
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Headers", "Content-Type")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_OPTIONS(self):
                self._send(200, {"status": "ok"})

            def do_GET(self):
                if self.path.split("?")[0] == "/health":
                    return self._send(200, node.health())
                self._send(404, {"status": "error", "error": "not found"})

            def _image(self) -> np.ndarray | None:
                ctype = self.headers.get("Content-Type", "")
                length = int(self.headers.get("Content-Length") or 0)
                if length <= 0:
                    return None
                raw: bytes | None = None
                body = self.rfile.read(length)
                if ctype.startswith("multipart/form-data"):
                    # stdlib only (the cgi module is gone in Python 3.13)
                    msg = BytesParser(policy=policy.default).parsebytes(
                        b"Content-Type: " + ctype.encode() + b"\r\n\r\n" + body)
                    for part in msg.iter_parts():
                        if part.get_param("name", header="content-disposition") == "image":
                            raw = part.get_payload(decode=True)
                            break
                else:
                    data = json.loads(body or b"{}")
                    if data.get("image_base64"):
                        raw = base64.b64decode(data["image_base64"])
                if not raw:
                    return None
                img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
                return img

            def do_POST(self):
                path = self.path.split("?")[0]
                try:
                    if path == "/analyze":
                        send = "send=0" not in self.path
                        out = node.run(self._image(), send=send)
                        return self._send(200 if out["status"] == "ok" else 409, out)
                    if path == "/phone":
                        length = int(self.headers.get("Content-Length") or 0)
                        data = json.loads(self.rfile.read(length) or b"{}")
                        if not data.get("ip"):
                            return self._send(400, {"status": "error", "error": "give {\"ip\": ...}"})
                        return self._send(200, node.set_phone(str(data["ip"]), int(data.get("port", 8765))))
                except Exception as e:  # noqa: BLE001
                    return self._send(500, {"status": "error", "error": str(e)[:300]})
                self._send(404, {"status": "error", "error": "not found"})

        server = ThreadingHTTPServer(("0.0.0.0", self.port), Handler)
        threading.Thread(target=server.serve_forever, daemon=True, name="vlm-node").start()
        print(f"[vlm-node] reachable at http://{local_ip()}:{self.port}  "
              f"(GET /health, POST /analyze, POST /phone)")
        return self
