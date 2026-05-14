#!/usr/bin/env python3
"""Small relay for CoRider development and small public deployments.

This is intentionally dependency-free. It keeps ride state in memory, streams
updates with Server-Sent Events, and stores only each rider's latest location.
For production at meaningful scale, place it behind HTTPS and add authenticated
ride membership tokens.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import re
import threading
import time
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, List
from urllib.parse import parse_qs, unquote, urlparse


STALE_AFTER_SECONDS = 90
ROOMS: Dict[str, "RideRoom"] = {}
ROOMS_LOCK = threading.Lock()


@dataclass
class Client:
    rider_id: str
    events: "queue.Queue[dict]" = field(default_factory=queue.Queue)


@dataclass
class RideRoom:
    clients: List[Client] = field(default_factory=list)
    latest: Dict[str, dict] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)


def get_room(ride_id: str) -> RideRoom:
    with ROOMS_LOCK:
        room = ROOMS.get(ride_id)
        if room is None:
            room = RideRoom()
            ROOMS[ride_id] = room
        return room


def clean_ride_id(raw: str) -> str:
    ride_id = unquote(raw).strip().upper()
    if not re.fullmatch(r"[A-Z0-9_-]{1,40}", ride_id):
        raise ValueError("ride id must use letters, numbers, underscore, or dash")
    return ride_id


def compact_json(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


class RideRelayHandler(BaseHTTPRequestHandler):
    server_version = "CoRiderRelay/0.1"

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self) -> None:
        try:
            if self.path == "/" or self.path == "/healthz":
                self.handle_health()
                return
            if self.path == "/debug":
                self.handle_debug()
                return
            ride_id, action = self.parse_ride_path()
            if action != "events":
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self.handle_events(ride_id)
        except ValueError as error:
            self.send_error(HTTPStatus.BAD_REQUEST, str(error))

    def do_POST(self) -> None:
        try:
            ride_id, action = self.parse_ride_path()
            if action == "location":
                self.handle_location(ride_id)
            elif action == "leave":
                self.handle_leave(ride_id)
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except ValueError as error:
            self.send_error(HTTPStatus.BAD_REQUEST, str(error))

    def handle_health(self) -> None:
        body = compact_json({"ok": True, "service": "corider-relay"})
        self.send_response(HTTPStatus.OK)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_debug(self) -> None:
        with ROOMS_LOCK:
            rooms = list(ROOMS.items())
        payload = {
            "rooms": {
                ride_id: {
                    "clients": len(room.clients),
                    "latest": sorted(room.latest.keys()),
                }
                for ride_id, room in rooms
            }
        }
        body = compact_json(payload)
        self.send_response(HTTPStatus.OK)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def parse_ride_path(self) -> tuple[str, str]:
        parsed = urlparse(self.path)
        parts = [part for part in parsed.path.split("/") if part]
        if len(parts) != 3 or parts[0] != "rides":
            raise ValueError("expected /rides/{rideId}/{action}")
        return clean_ride_id(parts[1]), parts[2]

    def handle_events(self, ride_id: str) -> None:
        parsed = urlparse(self.path)
        rider_id = parse_qs(parsed.query).get("riderId", [""])[0]
        client = Client(rider_id=rider_id)
        room = get_room(ride_id)

        self.send_response(HTTPStatus.OK)
        self.send_cors_headers()
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        with room.lock:
            room.clients.append(client)
            snapshot = list(room.latest.values())

        for payload in snapshot:
            if payload.get("u") != rider_id:
                self.write_event(payload)

        try:
            while True:
                try:
                    payload = client.events.get(timeout=15)
                    self.write_event(payload)
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        finally:
            with room.lock:
                if client in room.clients:
                    room.clients.remove(client)

    def handle_location(self, ride_id: str) -> None:
        payload = self.read_json()
        self.validate_location(payload)
        payload["y"] = "loc"
        room = get_room(ride_id)
        with room.lock:
            room.latest[payload["u"]] = payload
            clients = list(room.clients)

        for client in clients:
            client.events.put(payload)

        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_cors_headers()
        self.end_headers()

    def handle_leave(self, ride_id: str) -> None:
        payload = self.read_json()
        rider_id = str(payload.get("u", ""))
        room = get_room(ride_id)
        event = {"y": "left", "u": rider_id}
        with room.lock:
            room.latest.pop(rider_id, None)
            clients = list(room.clients)

        for client in clients:
            client.events.put(event)

        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_cors_headers()
        self.end_headers()

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 2048:
            raise ValueError("invalid payload length")
        raw = self.rfile.read(length)
        try:
            payload = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as error:
            raise ValueError("invalid json") from error
        if not isinstance(payload, dict):
            raise ValueError("payload must be an object")
        return payload

    def validate_location(self, payload: dict) -> None:
        required = ("u", "t", "a", "o")
        for key in required:
            if key not in payload:
                raise ValueError(f"missing {key}")
        payload["u"] = str(payload["u"])[:80]
        payload["n"] = str(payload.get("n", ""))[:40]
        payload["t"] = int(payload["t"])
        payload["a"] = int(payload["a"])
        payload["o"] = int(payload["o"])
        payload["s"] = int(payload.get("s", 0))
        payload["b"] = int(payload.get("b", -1))
        payload["c"] = int(payload.get("c", -1))
        if not -900_000_000 <= payload["a"] <= 900_000_000:
            raise ValueError("latitude is out of range")
        if not -1_800_000_000 <= payload["o"] <= 1_800_000_000:
            raise ValueError("longitude is out of range")

    def write_event(self, payload: dict) -> None:
        self.wfile.write(b"data: ")
        self.wfile.write(compact_json(payload))
        self.wfile.write(b"\n\n")
        self.wfile.flush()

    def send_cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def log_message(self, fmt: str, *args: object) -> None:
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))


def prune_loop() -> None:
    while True:
        time.sleep(20)
        now_ms = int(time.time() * 1000)
        with ROOMS_LOCK:
            rooms = list(ROOMS.items())
        for _, room in rooms:
            expired: List[str] = []
            with room.lock:
                for rider_id, payload in list(room.latest.items()):
                    if now_ms - int(payload.get("t", 0)) > STALE_AFTER_SECONDS * 1000:
                        expired.append(rider_id)
                        del room.latest[rider_id]
                clients = list(room.clients)
            for rider_id in expired:
                event = {"y": "left", "u": rider_id}
                for client in clients:
                    client.events.put(event)


def main() -> None:
    parser = argparse.ArgumentParser(description="CoRider local relay")
    parser.add_argument("--host", default=os.environ.get("HOST", "0.0.0.0"))
    parser.add_argument("--port", default=int(os.environ.get("PORT", "8080")), type=int)
    args = parser.parse_args()

    threading.Thread(target=prune_loop, name="prune-loop", daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), RideRelayHandler)
    print(f"CoRider relay listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
