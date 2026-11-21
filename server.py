"""Magic Transfer server — the HAND is the ID.

Phone A: display a photo, scan the hand -> the photo is mapped (bound) to that
hand's geometry signature (21 MediaPipe landmarks -> normalized ratios).
Phone B: scan the same hand -> server finds the closest matching signature
and returns the mapped photo.

No room code, no pairing. The hand itself is the address, like a fingerprint.
Photos live in memory and expire after 30 minutes.

Run:  pip install flask
      python server.py            (http://0.0.0.0:5000)
      cloudflared tunnel --url http://localhost:5000
"""
import json
import math
import time
import io

from flask import Flask, jsonify, request, send_file, abort

app = Flask(__name__, static_folder="static", static_url_path="")

# entries: [{"sig": [floats], "data": bytes, "mimetype": str, "name": str, "ts": float}]
entries = []
TTL_SECONDS = 1800       # a hand->photo binding lives 30 minutes
MATCH_THRESHOLD = 0.55   # max signature distance that still counts as "same hand"


def _cleanup():
    now = time.time()
    entries[:] = [e for e in entries if now - e["ts"] <= TTL_SECONDS]


def _distance(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


@app.get("/")
def index():
    return app.send_static_file("index.html")


@app.post("/bind")
def bind():
    """Sender: photo + hand signature -> store the mapping."""
    _cleanup()
    f = request.files.get("photo")
    sig_raw = request.form.get("sig", "")
    if f is None or not sig_raw:
        return jsonify({"ok": False, "error": "photo aur hand signature dono chahiye"}), 400
    try:
        sig = [float(x) for x in json.loads(sig_raw)]
    except (ValueError, TypeError):
        return jsonify({"ok": False, "error": "bad signature"}), 400
    entries.append({
        "sig": sig,
        "data": f.read(),
        "mimetype": f.mimetype or "image/jpeg",
        "name": f.filename or "photo",
        "ts": time.time(),
    })
    return jsonify({"ok": True, "bindings": len(entries)})


@app.post("/lookup")
def lookup():
    """Receiver: hand signature -> closest binding, if close enough."""
    _cleanup()
    body = request.get_json(silent=True) or {}
    sig = body.get("sig")
    if not sig:
        return jsonify({"found": False, "error": "no signature"}), 400
    best, best_d = None, None
    for i, e in enumerate(entries):
        if len(e["sig"]) != len(sig):
            continue
        d = _distance(sig, e["sig"])
        if best_d is None or d < best_d:
            best, best_d = i, d
    if best is not None and best_d <= MATCH_THRESHOLD:
        return jsonify({"found": True, "id": best, "distance": round(best_d, 3),
                        "name": entries[best]["name"]})
    return jsonify({"found": False,
                    "closest": round(best_d, 3) if best_d is not None else None})


@app.get("/fetch/<int:entry_id>")
def fetch(entry_id):
    _cleanup()
    if entry_id < 0 or entry_id >= len(entries):
        abort(404)
    e = entries[entry_id]
    return send_file(io.BytesIO(e["data"]), mimetype=e["mimetype"],
                     download_name=e["name"])


@app.get("/health")
def health():
    _cleanup()
    return jsonify({"ok": True, "bindings": len(entries)})


if __name__ == "__main__":
    import os
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "5000")), debug=False)
