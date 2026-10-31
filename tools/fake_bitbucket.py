#!/usr/bin/env python3
"""A Bitbucket Server stand-in for smoke: it re-derives what the client should have sent.

Bitbucket's build-status API is small enough to be re-implemented here in full, so the smoke does not trust the atom's
own idea of the contract:

    POST /rest/build-status/1.0/commits/{sha}   {"state","key","name","description","url"}  -> 204
    GET  /rest/build-status/1.0/commits/{sha}   {"values":[{"state","key",...},...]}        -> 200

On every POST this server checks the path, the credential, the JSON shape, the state vocabulary and the field lengths
itself, and refuses anything Bitbucket would refuse. What it stores is what it will hand back, so a read-back by the
client is a real read-back. --mode injects the ways a server (or a proxy) lies: accept and store something else, accept
and store nothing, answer 401/403, or break the read-back endpoint.
"""
import argparse
import json
import os
import re
import sys
import base64
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

STATES = {"SUCCESSFUL", "FAILED", "INPROGRESS"}
FIELDS = ("state", "key", "name", "description", "url")
PATHS = {"key": 255, "name": 255, "description": 255, "url": 450}
COMMIT = re.compile(r"^/rest/build-status/1\.0/commits/([^/]+)$")

LOCK = threading.Lock()


class Store:
    def __init__(self, path):
        self.path = path
        self.entries = {}  # sha -> [entry, ...] newest last

    def add(self, sha, entry):
        with LOCK:
            self.entries.setdefault(sha, []).append(entry)
            with open(self.path, "w", encoding="utf-8") as handle:
                json.dump(self.entries, handle, ensure_ascii=False)

    def values(self, sha):
        with LOCK:
            return list(reversed(self.entries.get(sha, [])))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def record(self, method, path, auth, note):
        with LOCK, open(self.server.log_path, "a", encoding="utf-8") as handle:
            handle.write(f"{method}\t{path}\t{auth}\t{note}\n")

    def reply(self, code, payload=None, content_type="application/json"):
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        if body:
            self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def authorized(self):
        header = self.headers.get("Authorization", "")
        if self.server.auth_mode == "bearer":
            return header == "Bearer " + self.server.credential
        if not header.startswith("Basic "):
            return False
        try:
            decoded = base64.b64decode(header[6:]).decode("utf-8")
        except Exception:
            return False
        return decoded == self.server.credential

    def do_POST(self):
        length = int(self.headers.get("content-length", 0))
        raw = self.rfile.read(length)
        auth = self.headers.get("Authorization", "none")
        match = COMMIT.match(self.path)
        if not match:
            self.record("POST", self.path, auth, "404 path")
            return self.reply(404, {"errors": [{"message": "no such resource"}]})
        sha = match.group(1)
        if self.server.expect_sha and sha != self.server.expect_sha:
            self.record("POST", self.path, auth, "404 unknown commit")
            return self.reply(404, {"errors": [{"message": "Commit not found"}]})

        if self.server.mode == "unauthorized" or not self.authorized():
            self.record("POST", self.path, auth, "401")
            return self.reply(401, {"errors": [{"message": "Authentication failed"}]})
        if self.server.mode == "forbidden":
            self.record("POST", self.path, auth, "403")
            return self.reply(403, {"errors": [{"message": "You are not permitted to write build status"}]})

        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            self.record("POST", self.path, auth, "415")
            return self.reply(415, {"errors": [{"message": "Unsupported media type"}]})
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception as error:
            self.record("POST", self.path, auth, f"400 json:{error}")
            return self.reply(400, {"errors": [{"message": "Malformed JSON"}]})
        if not isinstance(payload, dict) or set(payload) != set(FIELDS):
            self.record("POST", self.path, auth, f"400 fields:{sorted(payload) if isinstance(payload, dict) else payload}")
            return self.reply(400, {"errors": [{"message": "body must be exactly state/key/name/description/url"}]})
        if payload["state"] not in STATES:
            self.record("POST", self.path, auth, f"400 state:{payload['state']}")
            return self.reply(400, {"errors": [{"message": f"state must be one of {sorted(STATES)}"}]})
        for field, limit in PATHS.items():
            if not isinstance(payload[field], str) or len(payload[field]) > limit:
                self.record("POST", self.path, auth, f"400 {field}:{payload.get(field)}")
                return self.reply(400, {"errors": [{"message": f"{field} is a string of at most {limit} chars"}]})

        stored = dict(payload)
        note = "stored"
        if self.server.mode == "lying-drop":
            note = "204 but stored nothing"
            self.record("POST", self.path, auth, note)
            return self.reply(204)
        if self.server.mode == "lying-state":
            stored["state"] = "FAILED" if payload["state"] != "FAILED" else "SUCCESSFUL"
            note = f"204 but stored {stored['state']}"
        self.server.store.add(sha, stored)
        self.record("POST", self.path, auth, note + ":" + json.dumps(payload, ensure_ascii=False, sort_keys=True))
        return self.reply(204)

    def do_GET(self):
        auth = self.headers.get("Authorization", "none")
        match = COMMIT.match(self.path)
        if not match:
            self.record("GET", self.path, auth, "404 path")
            return self.reply(404, {"errors": [{"message": "no such resource"}]})
        sha = match.group(1)
        if self.server.mode == "get-405":
            self.record("GET", self.path, auth, "405")
            return self.reply(405, {"errors": [{"message": "Method not allowed"}]})
        if not self.authorized():
            self.record("GET", self.path, auth, "401")
            return self.reply(401, {"errors": [{"message": "Authentication failed"}]})
        values = self.server.store.values(sha)
        self.record("GET", self.path, auth, f"{len(values)} entries")
        return self.reply(200, {"values": values, "size": len(values), "isLastPage": True, "start": 0, "limit": 25})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--dir", required=True, help="where log-<port>.tsv and store-<port>.json go")
    parser.add_argument("--auth-mode", choices=["basic", "bearer"], default="basic")
    parser.add_argument("--credential", required=True)
    parser.add_argument("--expect-sha", default="")
    parser.add_argument("--mode", default="normal",
                        choices=["normal", "lying-drop", "lying-state", "unauthorized", "forbidden", "get-405"])
    args = parser.parse_args()
    server = HTTPServer(("127.0.0.1", args.port), Handler)
    port = server.server_address[1]
    # the caller only learns the port from the line below, so the file names are decided here
    args.log = os.path.join(args.dir, f"log-{port}.tsv")
    args.store = os.path.join(args.dir, f"store-{port}.json")
    open(args.log, "w", encoding="utf-8").close()
    with open(args.store, "w", encoding="utf-8") as handle:
        json.dump({}, handle)
    server.log_path = args.log
    server.store = Store(args.store)
    server.auth_mode = args.auth_mode
    server.credential = args.credential
    server.expect_sha = args.expect_sha
    server.mode = args.mode
    # --port 0 lets the kernel pick one: the smoke parallelises nothing but must never collide with a stale run
    print(f"listening {port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
