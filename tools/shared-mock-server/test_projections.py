#!/usr/bin/env python3
"""Unit tests for the shared-mock-server nested-relation projection helpers.

Run with:
    PYTHONDONTWRITEBYTECODE=1 python3 -m unittest tools/shared-mock-server/test_projections.py
"""

from __future__ import annotations

import importlib.util
import os
import sys
import unittest

# Load the server module without triggering __pycache__ writes.
_HERE = os.path.dirname(os.path.abspath(__file__))
_SERVER_PATH = os.path.join(_HERE, "server.py")
_spec = importlib.util.spec_from_file_location("mock_server", _SERVER_PATH)
_server = importlib.util.module_from_spec(_spec)
sys.modules["mock_server"] = _server
_spec.loader.exec_module(_server)


def _seed_pairing_code(code: str = "ABCDEFGH") -> None:
    """Seed a pairing code so pairing tests can consume it."""
    with _server._lock:
        _server._pairing_codes[code] = {
            "parent_id": _server.PARENT_ID,
            "device_name": "Test Tablet",
            "age_band": "7-12",
            "created_at": "2026-07-17T00:00:00Z",
            "ttl_minutes": 10,
        }


def _reset_devices() -> None:
    with _server._lock:
        _server._devices.clear()
        _server._time_requests.clear()
        # Parent-fix: clear the new side-effect queue so a stale event
        # from a prior test doesn't leak into the next one's assertions.
        if hasattr(_server, "_request_events"):
            _server._request_events.clear()


def _capture_handler(
    handler_fn,
    *,
    method: str = "GET",
    path: str = "/",
    body: dict | None = None,
) -> tuple[int, object]:
    """Invoke `handler_fn(stub[, body])` with a synthetic `BaseHTTPRequestHandler`
    stub and capture the (status, body) the handler would have written.
    Returns the first captured pair.
    """
    from http.server import BaseHTTPRequestHandler

    captured: list[tuple[int, object]] = []
    orig = _server._send_json

    def _fake_send(handler, status, body):
        captured.append((int(status), body))

    _server._send_json = _fake_send
    try:
        stub = BaseHTTPRequestHandler.__new__(BaseHTTPRequestHandler)
        stub.path = path
        stub.command = method
        stub.headers = {"Content-Length": str(len(str(body) if body else ""))}
        stub.rfile = type("R", (), {"read": lambda self, n=-1: b""})()
        stub.wfile = type("W", (), {})()
        handler_fn(stub, body) if body is not None else handler_fn(stub)
    finally:
        _server._send_json = orig
    return captured[0]


class PublicDeviceProjectionTest(unittest.TestCase):
    """Pure projection tests for `_public_device` (no socket, no mutation)."""

    def setUp(self) -> None:
        _reset_devices()

    def test_paired_device_without_child_first_name_projects_null_relation(self) -> None:
        """A device paired WITHOUT a captured child relation must project
        with `child_id: null` AND `child: null` — the production query
        explicitly selects `child_id`, so the top-level column MUST be
        present (null) on every row, not omitted. The dashboard
        surfaces 'Sin asignar' (child-entity spec)."""
        seeded = {
            "id": "device-child-abc",
            "device_name": "Test Tablet",
            "device_model": "unknown",
            "os_version": "0",
            "app_version": "1.0.0",
            "device_state": "ACTIVE",
            "policy_version": 1,
            "last_seen_at": "2026-07-17T00:00:00Z",
            "parent_id": _server.PARENT_ID,
            "_child_reported_name": "child",
        }
        out = _server._public_device(seeded)
        self.assertIsNone(out["child"], "child must be null on unassigned rows")
        # Production selects `child_id` explicitly — the key MUST be
        # present on every row (null when no relation).
        self.assertIn("child_id", out)
        self.assertIsNone(out["child_id"], "child_id must be present and null")

    def test_paired_device_with_captured_child_relation_projects_nested_object(self) -> None:
        """A device seeded with `_child_first_name` + `child_id` (e.g., from
        a test fixture, not from pairing) projects the nested relation."""
        seeded = {
            "id": "device-child-abc",
            "device_name": "Test Tablet",
            "device_state": "ACTIVE",
            "policy_version": 1,
            "last_seen_at": "2026-07-17T00:00:00Z",
            "parent_id": _server.PARENT_ID,
            "child_id": "child-sofia",
            "_child_first_name": "Sofía",
        }
        out = _server._public_device(seeded)
        self.assertEqual(
            out["child"],
            {"id": "child-sofia", "first_name": "Sofía"},
        )
        # The helper key must NOT leak into the wire response.
        self.assertNotIn("_child_first_name", out)
        self.assertNotIn("_child_reported_name", out)
        self.assertEqual(out["child_id"], "child-sofia")

    def test_projection_is_pure_and_does_not_mutate_input(self) -> None:
        """`_public_device` must be a pure function: it MUST NOT mutate the
        input dict (defence against callers aliasing shared state)."""
        seeded = {
            "id": "d1",
            "device_name": "Tablet",
            "device_state": "ACTIVE",
            "policy_version": 1,
            "last_seen_at": "2026-07-17T00:00:00Z",
            "parent_id": _server.PARENT_ID,
            "child_id": "child-x",
            "_child_first_name": "X",
        }
        snapshot = dict(seeded)
        _server._public_device(seeded)
        self.assertEqual(
            seeded, snapshot,
            "input dict must be unchanged after projection",
        )

    def test_projection_does_not_emit_flat_duplicate_relation_fields(self) -> None:
        """The wire response must NOT carry a flat `child_first_name`
        column — production never emits one."""
        seeded = {
            "id": "d1",
            "device_name": "Tablet",
            "device_state": "ACTIVE",
            "policy_version": 1,
            "last_seen_at": "2026-07-17T00:00:00Z",
            "parent_id": _server.PARENT_ID,
            "child_id": "child-x",
            "_child_first_name": "X",
        }
        out = _server._public_device(seeded)
        self.assertNotIn(
            "child_first_name", out,
            "flat child_first_name column must never appear in the wire response",
        )


class PublicTimeRequestProjectionTest(unittest.TestCase):
    """Pure projection tests for `_public_time_request`."""

    def test_known_device_id_projects_nested_device_name(self) -> None:
        """A row whose device_id matches a known device projects the
        nested `devices.device_name`."""
        _reset_devices()
        with _server._lock:
            _server._devices.append({
                "id": "dev-001",
                "device_name": "Galaxy Tab S6",
                "device_state": "ACTIVE",
                "policy_version": 1,
                "last_seen_at": "2026-07-17T00:00:00Z",
                "parent_id": _server.PARENT_ID,
            })
            row = {
                "id": "req-001",
                "device_id": "dev-001",
                "minutes_requested": 15,
                "status": "PENDING",
                "created_at": "2026-07-17T00:00:00Z",
            }
            device_by_id = {d["id"]: d for d in _server._devices}
            out = _server._public_time_request(row, device_by_id)
        self.assertEqual(out["devices"], {"device_name": "Galaxy Tab S6"})
        # Flat duplicate must NOT appear.
        self.assertNotIn("device_name", out)

    def test_orphaned_device_id_projects_null_devices_relation(self) -> None:
        """A row whose device_id no longer exists projects `devices: null`."""
        _reset_devices()
        row = {
            "id": "req-002",
            "device_id": "dev-ghost",
            "minutes_requested": 30,
            "status": "PENDING",
            "created_at": "2026-07-17T00:00:00Z",
        }
        out = _server._public_time_request(row, device_by_id={})
        self.assertIsNone(out["devices"])


class PairingBehaviourTest(unittest.TestCase):
    """Pairing MUST NOT invent a child relation. The production children-
    table join cannot be simulated by the mock inventing a child during
    pairing — the nested `child` projection on `get-devices-for-parent`
    must come from real children-table rows."""

    def setUp(self) -> None:
        _reset_devices()
        _seed_pairing_code("PAIR01")

    def _capture_pairing(self, body: dict) -> tuple[int, object]:
        return _capture_handler(
            _server.handle_pairing,
            method="POST",
            path="/functions/v1/pairing",
            body=body,
        )

    def test_pairing_with_child_first_name_does_not_synthesise_child_relation(self) -> None:
        """The Android client never sends `child_first_name`; even when a
        caller does, the mock MUST NOT invent a relation."""
        status, _body = self._capture_pairing({
            "code": "PAIR01",
            "device_name": "Child Tablet",
            "app_version": "1.0.0",
            "child_first_name": "Mateo",
        })
        self.assertEqual(status, 200)

        with _server._lock:
            row = list(_server._devices)[0]
        self.assertIsNone(row.get("child_id"))
        self.assertNotIn("_child_first_name", row)

    def test_pairing_without_child_first_name_leaves_device_unassigned(self) -> None:
        status, _body = self._capture_pairing({
            "code": "PAIR01",
            "device_name": "Child Tablet",
            "app_version": "1.0.0",
        })
        self.assertEqual(status, 200)

        with _server._lock:
            row = list(_server._devices)[0]
        self.assertIsNone(row.get("child_id"))
        self.assertNotIn("_child_first_name", row)

    def test_get_devices_projects_null_relation_for_pairing_only_device(self) -> None:
        """After pairing without an explicit child, `get-devices-for-parent`
        MUST return the device with `child_id: null` AND `child: null`
        — production's `select=..., child_id, child:children(...)` emits
        the `child_id` column on every row."""
        self._capture_pairing({
            "code": "PAIR01",
            "device_name": "Child Tablet",
            "app_version": "1.0.0",
        })

        status_code, devices = _capture_handler(_server.handle_get_devices)
        self.assertEqual(status_code, 200)
        self.assertEqual(len(devices), 1)
        self.assertIsNone(devices[0]["child"])
        self.assertIn("child_id", devices[0])
        self.assertIsNone(devices[0]["child_id"])

    def test_post_time_requests_response_omits_devices_relation(self) -> None:
        """`POST /rest/v1/time_requests` is a plain INSERT with
        `Prefer: return=representation` — it does NOT use the
        `select=*,devices(device_name)` embed. The mock must echo
        only the inserted row's columns, never fabricate a nested
        `devices` projection (that belongs to the GET)."""
        with _server._lock:
            _server._devices.append({
                "id": "dev-001",
                "device_name": "Galaxy Tab S6",
                "device_state": "ACTIVE",
                "policy_version": 1,
                "last_seen_at": "2026-07-17T00:00:00Z",
                "parent_id": _server.PARENT_ID,
            })
        status_code, rows = _capture_handler(
            _server.handle_post_time_requests,
            method="POST",
            path="/rest/v1/time_requests",
            body={"device_id": "dev-001", "minutes_requested": 15, "reason": "homework"},
        )
        self.assertEqual(status_code, 201)
        posted = rows[0]
        self.assertNotIn("devices", posted)
        # The inserted row's own columns are still present.
        self.assertEqual(posted["device_id"], "dev-001")
        self.assertEqual(posted["minutes_requested"], 15)
        self.assertEqual(posted["status"], "PENDING")


class SetDeviceStateTest(unittest.TestCase):
    """WU-2 — set-device-state mock routes."""

    def setUp(self) -> None:
        with _server._lock:
            _server._devices.clear()
            _server._devices.append({
                "id": "dev-existing",
                "device_name": "Existing Tablet",
                "device_state": "ACTIVE",
                "policy_version": 5,
                "last_seen_at": "2026-07-17T00:00:00Z",
                "parent_id": _server.PARENT_ID,
            })

    def _capture(self, body, with_auth=True):
        from http.server import BaseHTTPRequestHandler

        captured = []
        orig = _server._send_json

        def _fake_send(handler, status, b):
            captured.append((int(status), b))

        _server._send_json = _fake_send
        try:
            stub = BaseHTTPRequestHandler.__new__(BaseHTTPRequestHandler)
            stub.path = "/functions/v1/set-device-state"
            stub.command = "POST"
            stub.headers = (
                {"Authorization": "Bearer test-jwt"} if with_auth else {}
            )
            stub.rfile = type("R", (), {"read": lambda self, n=-1: b""})()
            stub.wfile = type("W", (), {})()
            _server.handle_set_device_state(stub, body)
        finally:
            _server._send_json = orig
        return captured[0]

    def test_lock_existing_device_bumps_state_and_policy_version(self) -> None:
        status, body = self._capture(
            {"device_id": "dev-existing", "state": "LOCKED"})
        self.assertEqual(status, 200)
        self.assertEqual(body["state"], "LOCKED")
        self.assertEqual(body["policy_version"], 6)
        with _server._lock:
            dev = next(d for d in _server._devices if d["id"] == "dev-existing")
        self.assertEqual(dev["device_state"], "LOCKED")
        self.assertEqual(dev["policy_version"], 6)

    def test_unlock_existing_device_bumps_state_and_policy_version(self) -> None:
        _, body = self._capture(
            {"device_id": "dev-existing", "state": "ACTIVE"})
        self.assertEqual(body["state"], "ACTIVE")
        self.assertEqual(body["policy_version"], 6)

    def test_missing_token_returns_401(self) -> None:
        status, body = self._capture(
            {"device_id": "dev-existing", "state": "LOCKED"}, with_auth=False)
        self.assertEqual(status, 401)
        self.assertIn("Token requerido", body["error"])

    def test_unknown_device_id_returns_404(self) -> None:
        # F2c — shared mock must align with production RLS strict 404
        # (no auto-created row). The previous permissive mock created
        # a row on unknown device_id which masked client-side validation
        # bugs. Production RLS rejects unknown device_id; the mock
        # mirrors that.
        status, body = self._capture(
            {"device_id": "dev-does-not-exist", "state": "LOCKED"})
        self.assertEqual(status, 404)
        self.assertIn("no encontrado", body["error"])

    def test_invalid_state_returns_422(self) -> None:
        status, body = self._capture(
            {"device_id": "dev-existing", "state": "DELETED"})
        self.assertEqual(status, 422)
        self.assertIn("LOCKED", body["error"])


class RequestCreatedSideEffectTest(unittest.TestCase):
    """Parent-fix — POST /rest/v1/time_requests must trigger a
    non-blocking side effect that pushes a `request_created` event onto
    the parent's realtime stream, so the parent sees the request in
    seconds instead of waiting up to 5 min for `SolicitudesPollingWorker`.
    The shared mock mirrors that by spawning a daemon thread that logs
    the event and appends it to `_request_events` — an in-process queue
    this test pokes."""

    def setUp(self) -> None:
        with _server._lock:
            _server._devices.clear()
            _server._time_requests.clear()
            _server._request_events.clear()
        with _server._lock:
            _server._devices.append({
                "id": "dev-A",
                "device_name": "Tablet A",
                "device_state": "ACTIVE",
                "policy_version": 1,
                "last_seen_at": "2026-07-21T00:00:00Z",
                "parent_id": _server.PARENT_ID,
            })

    def _wait_for(self, request_id, timeout=1.0):
        """Poll `_server._request_events` until the matching event lands
        or `timeout` expires. The POST handler fires a daemon thread to
        append the event — we wait for that thread without starting the
        HTTP server (per the no-real-socket constraint)."""
        import time
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            with _server._lock:
                events = list(_server._request_events.get(_server.PARENT_ID, []))
            if any(e["request_id"] == request_id for e in events):
                return events
            time.sleep(0.01)
        return events  # last snapshot for the assertion error message

    def test_post_time_requests_enqueues_request_created_event(self):
        """POST /rest/v1/time_requests must emit a non-blocking
        `request_created` event keyed by parent_id and carrying the new
        `request_id`, so a parent subscribed to the realtime channel sees
        the request within seconds."""
        status_code, rows = _capture_handler(
            _server.handle_post_time_requests,
            method="POST",
            path="/rest/v1/time_requests",
            body={"device_id": "dev-A", "minutes_requested": 15, "reason": "homework"},
        )
        self.assertEqual(status_code, 201)
        request_id = rows[0]["id"]

        events = self._wait_for(request_id)
        self.assertTrue(
            any(e["request_id"] == request_id for e in events),
            f"request_created side-effect for {request_id} never landed "
            f"in _request_events within 1s; events={events!r}",
        )
        matched = next(e for e in events if e["request_id"] == request_id)
        self.assertEqual(matched["event"], "request_created")
        self.assertEqual(matched["parent_id"], _server.PARENT_ID)

    def test_post_time_requests_response_is_not_delayed_by_side_effect(self):
        """The POST /rest/v1/time_requests response must come back
        WITHOUT waiting on the daemon thread — otherwise a slow channel
        would block the child's optimistic UI. Verify by capturing the
        latency between the handler returning and the queue being
        populated; we accept anything up to 50ms (the thread spawn
        itself must not be in the request path)."""
        import time
        with _server._lock:
            _server._request_events.clear()
        t0 = time.monotonic()
        status_code, rows = _capture_handler(
            _server.handle_post_time_requests,
            method="POST",
            path="/rest/v1/time_requests",
            body={"device_id": "dev-A", "minutes_requested": 5, "reason": "snack"},
        )
        elapsed_ms = (time.monotonic() - t0) * 1000.0
        self.assertEqual(status_code, 201)
        self.assertLess(
            elapsed_ms, 50.0,
            f"handler took {elapsed_ms:.1f}ms — side-effect appears to "
            f"be running on the request path instead of a daemon thread",
        )
        # And the thread still lands eventually.
        request_id = rows[0]["id"]
        events = self._wait_for(request_id)
        self.assertTrue(
            any(e["request_id"] == request_id for e in events),
            f"daemon thread should still have appended {request_id}",
        )


class GetPolicyTest(unittest.TestCase):
    """R4 — `GET /functions/v1/get-policy` reflects the live
    `device_state` so the child picks up a parent's lock/unlock on
    the next pullPolicy cycle (NextSync propagation)."""

    def setUp(self) -> None:
        with _server._lock:
            _server._devices.clear()
            _server._devices.append({
                "id": "dev-existing",
                "device_name": "Existing Tablet",
                "device_state": "ACTIVE",
                "policy_version": 5,
                "last_seen_at": "2026-07-17T00:00:00Z",
                "parent_id": _server.PARENT_ID,
            })

    def _capture_get_policy(self):
        from http.server import BaseHTTPRequestHandler
        captured = []
        orig = _server._send_json
        def _fake_send(handler, status, b):
            captured.append((int(status), b))
        _server._send_json = _fake_send
        try:
            stub = BaseHTTPRequestHandler.__new__(BaseHTTPRequestHandler)
            stub.path = "/functions/v1/get-policy"
            stub.command = "GET"
            stub.headers = {}
            stub.rfile = type("R", (), {"read": lambda self, n=-1: b""})()
            stub.wfile = type("W", (), {})()
            _server.handle_get_policy(stub)
        finally:
            _server._send_json = orig
        return captured[0]

    def test_get_policy_returns_active_initially(self) -> None:
        status, body = self._capture_get_policy()
        self.assertEqual(200, status)
        self.assertEqual("ACTIVE", body[0]["device_state"])
        self.assertEqual(5, body[0]["version"])

    def test_get_policy_reflects_lock_after_set_device_state(self) -> None:
        from http.server import BaseHTTPRequestHandler
        captured = []
        orig = _server._send_json
        def _fake_send(handler, status, b):
            captured.append((int(status), b))
        _server._send_json = _fake_send
        try:
            stub = BaseHTTPRequestHandler.__new__(BaseHTTPRequestHandler)
            stub.path = "/functions/v1/set-device-state"
            stub.command = "POST"
            stub.headers = {"Authorization": "Bearer test-jwt"}
            stub.rfile = type("R", (), {"read": lambda self, n=-1: b""})()
            stub.wfile = type("W", (), {})()
            _server.handle_set_device_state(
                stub, {"device_id": "dev-existing", "state": "LOCKED"}
            )
        finally:
            _server._send_json = orig
        status, body = self._capture_get_policy()
        self.assertEqual(200, status)
        self.assertEqual(
            "LOCKED",
            body[0]["device_state"],
            "get-policy must reflect the live device_state after a " +
                "set-device-state call so the child's NextSync " +
                "propagation sees the parent's lock.",
        )

    def test_get_policy_reflects_unlock(self) -> None:
        from http.server import BaseHTTPRequestHandler
        captured = []
        orig = _server._send_json
        def _fake_send(handler, status, b):
            captured.append((int(status), b))
        _server._send_json = _fake_send
        try:
            for state in ("LOCKED", "ACTIVE"):
                stub = BaseHTTPRequestHandler.__new__(BaseHTTPRequestHandler)
                stub.path = "/functions/v1/set-device-state"
                stub.command = "POST"
                stub.headers = {"Authorization": "Bearer test-jwt"}
                stub.rfile = type("R", (), {"read": lambda self, n=-1: b""})()
                stub.wfile = type("W", (), {})()
                _server.handle_set_device_state(
                    stub, {"device_id": "dev-existing", "state": state}
                )
        finally:
            _server._send_json = orig
        status, body = self._capture_get_policy()
        self.assertEqual(200, status)
        self.assertEqual("ACTIVE", body[0]["device_state"])


if __name__ == "__main__":
    unittest.main()