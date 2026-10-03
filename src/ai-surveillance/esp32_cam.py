"""esp32_cam.py - use the ESP32-CAM feed whenever it is available. No code changes.

Drop-in: this file patches cv2.VideoCapture at runtime. Anything in this repo
that opens a *webcam index* (cv2.VideoCapture(0), WebcamSource, ...) gets the
ESP32-CAM stream instead while the ESP32 is reachable, and falls back to the
laptop webcam the moment the ESP32 drops. When the ESP32 comes back, it
switches back on its own. Files, RTSP URLs and every other cv2 call are
untouched.

-----------------------------------------------------------------------------
HOW TO RUN (pick one)
-----------------------------------------------------------------------------
  1) Launcher - prefix any normal command with `esp32_cam.py`:

       python esp32_cam.py run_vlm_to_bitchat.py
       python esp32_cam.py run_vlm_only.py
       python esp32_cam.py -m pipeline.main_loop

  2) Always-on - hook every run started from this folder, then use the
     usual commands unchanged (python run_vlm_to_bitchat.py ...):

       python esp32_cam.py --install      # once, in the venv you run with
       python esp32_cam.py --uninstall    # to remove

  Check the feed without loading the VLM:

       python esp32_cam.py --test         # opens a preview window, Q quits

-----------------------------------------------------------------------------
WHERE THE FEED COMES FROM
-----------------------------------------------------------------------------
  Set ESP32_CAM_URL below or as an environment variable (env wins):

    http://192.168.1.50:81/stream   ESP32 CameraWebServer MJPEG stream (default sketch)
    http://192.168.1.50/capture     single-JPEG endpoint, polled
    http://my-server:8000/esp/stream  any relay server that re-serves MJPEG or JPEG
    auto                            scan this Wi-Fi (/24) for an ESP32 on port 81
    off                             disable, behave exactly like before

  Windows PowerShell:  $env:ESP32_CAM_URL = "http://192.168.1.50:81/stream"
  cmd:                 set ESP32_CAM_URL=http://192.168.1.50:81/stream

  Note: the stock ESP32 sketch serves ONE stream client at a time. Close the
  ESP32 page in your browser before running, or this will not get frames.
"""
from __future__ import annotations

import os
import socket
import sys
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

# --- settings (env vars override these) --------------------------------------
ESP32_CAM_URL = os.environ.get("ESP32_CAM_URL", "auto")
STALE_AFTER_S = float(os.environ.get("ESP32_CAM_STALE_S", "2.0"))   # no frame this long -> ESP32 is "down"
STARTUP_WAIT_S = float(os.environ.get("ESP32_CAM_STARTUP_S", "6.0"))  # wait this long for the ESP32 at open()
RESIZE_MODE = os.environ.get("ESP32_CAM_RESIZE", "letterbox")       # letterbox | stretch | none
FALLBACK_TO_WEBCAM = os.environ.get("ESP32_CAM_FALLBACK", "1") != "0"
ROTATE = int(os.environ.get("ESP32_CAM_ROTATE", "0"))               # 0 | 90 | 180 | 270 (ESP32 boards are often mounted flipped)
# -----------------------------------------------------------------------------

_HERE = os.path.dirname(os.path.abspath(__file__))
_PTH_NAME = "zz_esp32_cam_hook.pth"
_installed = False


def _log(msg: str) -> None:
    print(f"[esp32] {msg}", flush=True)


# =============================================================================
# Finding the ESP32
# =============================================================================
def _local_ipv4() -> str | None:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return None


def _looks_like_esp_stream(url: str) -> bool:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "esp32_cam.py"})
        with _opener.open(req, timeout=1.5) as r:
            ctype = r.headers.get("Content-Type", "")
            return "multipart" in ctype or "jpeg" in ctype
    except Exception:
        return False


def _discover() -> str | None:
    """Scan the laptop's /24 for something answering an MJPEG stream on :81."""
    me = _local_ipv4()
    if not me:
        return None
    prefix = me.rsplit(".", 1)[0]

    def port_open(host: str) -> str | None:
        try:
            with socket.create_connection((host, 81), timeout=0.4):
                return host
        except Exception:
            return None

    hosts = [f"{prefix}.{i}" for i in range(1, 255) if f"{prefix}.{i}" != me]
    with ThreadPoolExecutor(max_workers=64) as ex:
        candidates = [h for h in ex.map(port_open, hosts) if h]
    for h in candidates:
        url = f"http://{h}:81/stream"
        if _looks_like_esp_stream(url):
            return url
    return None


# Direct connections only: a system HTTP proxy must not swallow LAN traffic.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


# =============================================================================
# Background reader: keeps only the newest ESP32 frame
# =============================================================================
class _EspReader(threading.Thread):
    def __init__(self, url: str):
        super().__init__(daemon=True, name="esp32-reader")
        self.url_setting = url
        self.url: str | None = None if url.lower() == "auto" else url
        self._lock = threading.Lock()
        self._new = threading.Condition(self._lock)
        self._frame = None
        self._seq = 0
        self._t = 0.0
        self._stop = threading.Event()
        self._fps = 0.0

    # -- public ----------------------------------------------------------
    def latest(self):
        with self._lock:
            return self._frame, self._seq, self._t

    def wait_newer(self, seq: int, timeout: float):
        with self._new:
            if self._seq <= seq:
                self._new.wait(timeout)
            return self._frame, self._seq, self._t

    def alive(self) -> bool:
        return self._frame is not None and (time.monotonic() - self._t) < STALE_AFTER_S

    def stop(self) -> None:
        self._stop.set()

    # -- loop ------------------------------------------------------------
    def run(self) -> None:
        import cv2
        import numpy as np

        backoff = 1.0
        last_discovery = 0.0
        while not self._stop.is_set():
            if self.url is None:
                if time.monotonic() - last_discovery < 15:
                    time.sleep(1)
                    continue
                last_discovery = time.monotonic()
                _log("auto: scanning this network for an ESP32-CAM on port 81 ...")
                self.url = _discover()
                if self.url:
                    _log(f"auto: found {self.url}")
                else:
                    _log("auto: none found (will rescan). Set ESP32_CAM_URL to skip scanning.")
                    continue
            try:
                if self._is_snapshot_url(self.url):
                    self._poll_snapshots(cv2, np)
                else:
                    self._read_mjpeg(cv2, np)
                backoff = 1.0
            except Exception as e:  # network drop, ESP32 rebooted, ...
                if self._frame is not None and self.alive():
                    _log(f"stream lost: {e}")
                time.sleep(backoff)
                backoff = min(backoff * 2, 3.0)
                if self.url_setting.lower() == "auto" and backoff >= 3.0:
                    self.url = None  # IP may have changed; rediscover

    @staticmethod
    def _is_snapshot_url(url: str) -> bool:
        tail = url.rstrip("/").rsplit("/", 1)[-1].lower()
        return tail in ("capture", "jpg", "snapshot", "photo", "shot.jpg") or tail.endswith(".jpg")

    def _publish(self, jpg: bytes, cv2, np) -> None:
        img = cv2.imdecode(np.frombuffer(jpg, dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            return
        if ROTATE == 90:
            img = cv2.rotate(img, cv2.ROTATE_90_CLOCKWISE)
        elif ROTATE == 180:
            img = cv2.rotate(img, cv2.ROTATE_180)
        elif ROTATE == 270:
            img = cv2.rotate(img, cv2.ROTATE_90_COUNTERCLOCKWISE)
        now = time.monotonic()
        with self._new:
            if self._t:
                dt = now - self._t
                if dt > 0:
                    self._fps = 0.9 * self._fps + 0.1 * (1.0 / dt) if self._fps else 1.0 / dt
            self._frame, self._t = img, now
            self._seq += 1
            self._new.notify_all()

    def _read_mjpeg(self, cv2, np) -> None:
        req = urllib.request.Request(self.url, headers={"User-Agent": "esp32_cam.py"})
        with _opener.open(req, timeout=5) as r:
            buf = b""
            while not self._stop.is_set():
                chunk = r.read(4096)
                if not chunk:
                    raise ConnectionError("stream closed")
                buf += chunk
                # Take the LAST complete JPEG in the buffer; drop older ones (low latency).
                end = buf.rfind(b"\xff\xd9")
                if end == -1:
                    if len(buf) > 4_000_000:
                        buf = b""
                    continue
                start = buf.rfind(b"\xff\xd8", 0, end)
                if start == -1:
                    buf = buf[end + 2:]
                    continue
                self._publish(buf[start:end + 2], cv2, np)
                buf = buf[end + 2:]

    def _poll_snapshots(self, cv2, np) -> None:
        while not self._stop.is_set():
            req = urllib.request.Request(self.url, headers={"User-Agent": "esp32_cam.py"})
            with _opener.open(req, timeout=5) as r:
                self._publish(r.read(), cv2, np)


# =============================================================================
# The cv2.VideoCapture stand-in
# =============================================================================
def _make_hybrid(RealCapture):
    import cv2
    import numpy as np

    class HybridCapture:
        """Behaves like cv2.VideoCapture(index): ESP32 when alive, webcam otherwise."""

        def __init__(self, index, *args, **kwargs):
            self._index, self._args, self._kwargs = index, args, kwargs
            self._props: dict[int, float] = {}
            self._webcam = None
            self._seq = 0
            self._using = None  # "esp32" | "webcam"
            self._released = False
            self._last_wait_log = 0.0
            self._webcam_next_try = 0.0
            self._webcam_warned = False
            self._reader = _EspReader(ESP32_CAM_URL)
            self._reader.start()

            deadline = time.monotonic() + STARTUP_WAIT_S
            while time.monotonic() < deadline and not self._reader.alive():
                time.sleep(0.1)
            if self._reader.alive():
                self._switch("esp32")
            elif FALLBACK_TO_WEBCAM:
                _log(f"ESP32 not available yet - starting on webcam {index}; will switch when it appears")
                if self._open_webcam() is not None:
                    self._switch("webcam")
            else:
                _log("ESP32 not available yet - waiting for it (webcam fallback is off)")

        # -- helpers ---------------------------------------------------------
        def _open_webcam(self):
            # Retry a missing webcam only every 5 s: opening a camera is slow and noisy.
            if self._webcam is None and FALLBACK_TO_WEBCAM and time.monotonic() >= self._webcam_next_try:
                cap = RealCapture(self._index, *self._args, **self._kwargs)
                for k, v in self._props.items():
                    cap.set(k, v)
                if cap.isOpened():
                    self._webcam = cap
                else:
                    cap.release()
                    self._webcam_next_try = time.monotonic() + 5.0
                    if not self._webcam_warned:
                        _log(f"webcam {self._index} could not be opened (will retry)")
                        self._webcam_warned = True
            return self._webcam

        def _switch(self, which):
            if which != self._using:
                if which == "esp32":
                    f, _, _ = self._reader.latest()
                    shape = f"{f.shape[1]}x{f.shape[0]}" if f is not None else "?"
                    _log(f">>> using ESP32-CAM feed  ({self._reader.url}, {shape})")
                else:
                    _log(f">>> using laptop webcam {self._index}")
                self._using = which

        def _fit(self, frame):
            w = int(self._props.get(cv2.CAP_PROP_FRAME_WIDTH, 0) or 0)
            h = int(self._props.get(cv2.CAP_PROP_FRAME_HEIGHT, 0) or 0)
            if RESIZE_MODE == "none" or not w or not h:
                return frame
            fh, fw = frame.shape[:2]
            if (fw, fh) == (w, h):
                return frame
            if RESIZE_MODE == "stretch":
                return cv2.resize(frame, (w, h), interpolation=cv2.INTER_LINEAR)
            s = min(w / fw, h / fh)
            nw, nh = max(1, int(fw * s)), max(1, int(fh * s))
            out = np.zeros((h, w, 3), dtype=frame.dtype)
            x, y = (w - nw) // 2, (h - nh) // 2
            out[y:y + nh, x:x + nw] = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_LINEAR)
            return out

        # -- cv2.VideoCapture API -------------------------------------------------
        def isOpened(self):
            if self._released:
                return False
            return self._reader.alive() or self._webcam is not None or not FALLBACK_TO_WEBCAM

        def read(self, image=None):
            # Never returns (False, None) just because a camera hiccuped: callers
            # like pipeline.main_loop treat that as "source ended" and quit.
            while not self._released:
                if self._reader.alive():
                    frame, seq, _ = self._reader.wait_newer(self._seq, timeout=STALE_AFTER_S)
                    if frame is not None and self._reader.alive():
                        self._seq = seq
                        self._switch("esp32")
                        return True, self._fit(frame)
                cam = self._open_webcam()
                if cam is not None:
                    ok, frame = cam.read()
                    if ok and frame is not None:
                        self._switch("webcam")
                        return True, frame
                now = time.monotonic()
                if now - self._last_wait_log > 5:
                    _log("no camera frames (ESP32 down, webcam unavailable) - waiting ...")
                    self._last_wait_log = now
                time.sleep(0.1)
            return False, None

        def grab(self):
            return not self._released

        def retrieve(self, image=None, flag=0):
            return self.read()

        def set(self, prop, value):
            self._props[prop] = value
            if self._webcam is not None:
                self._webcam.set(prop, value)
            return True

        def get(self, prop):
            if self._using == "esp32":
                f, _, _ = self._reader.latest()
                if f is not None:
                    f = self._fit(f)
                    if prop == cv2.CAP_PROP_FRAME_WIDTH:
                        return float(f.shape[1])
                    if prop == cv2.CAP_PROP_FRAME_HEIGHT:
                        return float(f.shape[0])
                if prop == cv2.CAP_PROP_FPS:
                    return float(self._reader._fps or self._props.get(prop, 0) or 0)
            if self._webcam is not None:
                return self._webcam.get(prop)
            return float(self._props.get(prop, 0))

        def release(self):
            self._released = True
            self._reader.stop()
            if self._webcam is not None:
                self._webcam.release()
                self._webcam = None

        def getBackendName(self):
            return "ESP32-MJPEG" if self._using == "esp32" else "WEBCAM"

        def __del__(self):
            try:
                self.release()
            except Exception:
                pass

    return HybridCapture


def install() -> None:
    """Patch cv2.VideoCapture so webcam indices go through the ESP32 first."""
    global _installed
    if _installed or ESP32_CAM_URL.strip().lower() in ("", "off", "0", "none", "false"):
        return
    import cv2

    if getattr(cv2.VideoCapture, "_real", None) is not None:  # already hooked (e.g. .pth + launcher)
        _installed = True
        return
    RealCapture = cv2.VideoCapture
    Hybrid = _make_hybrid(RealCapture)

    def VideoCapture(*args, **kwargs):
        src = args[0] if args else kwargs.get("index", kwargs.get("filename"))
        if isinstance(src, int) or (isinstance(src, str) and src.isdigit()):
            rest = args[1:] if args else ()
            return Hybrid(int(src), *rest)
        return RealCapture(*args, **kwargs)  # files, RTSP, URLs: untouched

    VideoCapture._real = RealCapture  # type: ignore[attr-defined]
    cv2.VideoCapture = VideoCapture
    _installed = True
    _log(f"hook active - webcam opens prefer ESP32 ({ESP32_CAM_URL})")


# =============================================================================
# .pth auto-hook (optional)
# =============================================================================
def _site_dir() -> str:
    import site
    import sysconfig
    if sys.prefix != sys.base_prefix:  # inside a venv
        return sysconfig.get_paths()["purelib"]
    return site.getusersitepackages()


def _pth_install() -> None:
    d = _site_dir()
    os.makedirs(d, exist_ok=True)
    line = (
        "import os, sys; _d = %r; "
        "(os.path.normcase(os.getcwd()).startswith(os.path.normcase(_d)) "
        "and os.path.exists(os.path.join(_d, 'esp32_cam.py')) "
        "and (sys.path.insert(0, _d) or __import__('esp32_cam').install()))\n" % _HERE
    )
    path = os.path.join(d, _PTH_NAME)
    with open(path, "w", encoding="utf-8") as f:
        f.write(line)
    print(f"Installed auto-hook: {path}")
    print(f"Every Python run started inside {_HERE} now prefers the ESP32 feed.")


def _pth_uninstall() -> None:
    path = os.path.join(_site_dir(), _PTH_NAME)
    if os.path.exists(path):
        os.remove(path)
        print(f"Removed {path}")
    else:
        print("Auto-hook was not installed.")


def _selftest() -> None:
    install()
    import cv2
    cap = cv2.VideoCapture(0)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)
    t0, n = time.time(), 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        n += 1
        if time.time() - t0 >= 1:
            sys.stdout.write(f"\r[esp32] {cap.getBackendName():12s} {n / (time.time() - t0):5.1f} fps   ")
            sys.stdout.flush()
            t0, n = time.time(), 0
        cv2.imshow("esp32_cam test (Q to quit)", frame)
        if cv2.waitKey(1) & 0xFF in (ord("q"), 27):
            break
    cap.release()
    cv2.destroyAllWindows()


def _launch(argv: list[str]) -> None:
    """python esp32_cam.py <script.py | -m module> [args...]"""
    import runpy
    install()
    if _HERE not in sys.path:
        sys.path.insert(0, _HERE)
    if argv[0] == "-m":
        if len(argv) < 2:
            sys.exit("usage: python esp32_cam.py -m <module> [args]")
        sys.argv = [argv[1]] + argv[2:]
        runpy.run_module(argv[1], run_name="__main__", alter_sys=True)
    else:
        script = os.path.abspath(argv[0])
        sys.argv = [script] + argv[1:]
        sys.path.insert(0, os.path.dirname(script))
        runpy.run_path(script, run_name="__main__")


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
    elif args[0] == "--install":
        _pth_install()
    elif args[0] == "--uninstall":
        _pth_uninstall()
    elif args[0] == "--test":
        _selftest()
    else:
        _launch(args)
