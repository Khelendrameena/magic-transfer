"""Magic Transfer server.

Both phones open the same web page and type the same 4-digit room code.
Phone A: show a photo, hold an open palm at the camera -> photo uploads.
Phone B: hold an open palm -> the photo appears.

The server is the invisible middleman (like WhatsApp Web) — the user never
pairs, scans or connects anything. Photos are kept in memory per room and
expire after 10 minutes.

Run:  pip install flask
      python server.py            (serves on http://0.0.0.0:5000)
Then expose it with cloudflared (same pattern as Adda):
      cloudflared tunnel --url http://localhost:5000
"""
import time

from flask import Flask, jsonify, request, send_file, abort
import io

app = Flask(__name__, static_folder="static", static_url_path="")

# room_code -> {"data": bytes, "mimetype": str, "ts": float, "name": str}
rooms = {}
TTL_SECONDS = 600  # photo auto-expires after 10 min


def _cleanup():
    now = time.time()
    for code in [c for c, v in rooms.items() if now - v["ts"] > TTL_SECONDS]:
        del rooms[code]


@app.get("/")
def index():
    return app.send_static_file("index.html")


@app.post("/room/<code>/grab")
def grab(code):
    _cleanup()
    f = request.files.get("photo")
    if f is None:
        return jsonify({"ok": False, "error": "no photo"}), 400
    rooms[code] = {
        "data": f.read(),
        "mimetype": f.mimetype or "image/jpeg",
        "ts": time.time(),
        "name": f.filename or "photo",
    }
    return jsonify({"ok": True, "ts": rooms[code]["ts"]})


@app.get("/room/<code>/peek")
def peek(code):
    """Receiver polls this: is there a photo newer than ?since=<ts>?"""
    _cleanup()
    since = request.args.get("since", default=0, type=float)
    entry = rooms.get(code)
    if entry and entry["ts"] > since:
        return jsonify({"available": True, "ts": entry["ts"], "name": entry["name"]})
    return jsonify({"available": False})


@app.get("/room/<code>/photo")
def photo(code):
    entry = rooms.get(code)
    if entry is None:
        abort(404)
    return send_file(
        io.BytesIO(entry["data"]),
        mimetype=entry["mimetype"],
        download_name=entry["name"],
    )


@app.get("/health")
def health():
    return jsonify({"ok": True, "rooms": len(rooms)})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
