#!/usr/bin/env python3
"""
Shared mock Supabase backend for cross-device pairing tests.

Mirrors the wire shapes of the static fixtures in
`app/src/main/assets/mock-supabase/` but keeps mutable state in memory so two
Android devices (phone + emulator) running against this server can actually
exchange pairing codes and see each other in the parent's device list.

Endpoints implemented:
  POST /auth/v1/token                                 -> anon auth
  POST /functions/v1/create-pairing-code              -> parent generates code
  POST /functions/v1/pairing                          -> child consumes code
  GET  /functions/v1/get-devices-for-parent           -> parent lists devices
  GET  /functions/v1/get-templates                    -> template list (empty)
  GET  /rest/v1/time_requests                         -> pending requests (empty)
  POST /rest/v1/time_requests                         -> create time request

Unknown paths return 404 with `{"error":"unknown route <path>"}`.

Run:
  python3 tools/shared-mock-server/server.py [--port 8787] [--host 0.0.0.0]

Then on each Android device (with USE_SHARED_MOCK=true and
SHARED_MOCK_URL=http://<host-ip>:8787 baked into the APK):
  adb -s <serial> reverse tcp:8787 tcp:8787

The phone reaches the server via the reverse-tunnel; the emulator can reach
the host directly via 10.0.2.2:8787 or via reverse-tunnel as well.
"""

from __future__ import annotations

import argparse
import datetime as _dt
import http.server
import json
import secrets
import string
import threading
from http import HTTPStatus
from urllib.parse import parse_qs, urlparse

# ---------------------------------------------------------------------------
# In-memory state — process-wide singleton protected by a lock.
# ---------------------------------------------------------------------------

_lock = threading.Lock()

# Fixed "parent" identity. In a real Supabase setup this would be the
# authenticated user's UUID. We keep it constant so any device that finishes
# the anon auth handshake ends up as the same logical parent.
PARENT_ID = "00000000-0000-0000-0000-000000000001"

# Pending pairing codes, keyed by the 8-char code (uppercase). Value carries
# the parent_id and the device_name the parent typed in the "Emparejar
# dispositivo" sheet — so when the child consumes the code we know what to
# show in the parent's device list.
_pairing_codes: dict[str, dict] = {}

# Registered child devices. Each entry matches the wire shape returned by
# `get-devices-for-parent` (see `assets/mock-supabase/devices.json`).
_devices: list[dict] = []

# Pending time requests from children (the "Pedir tiempo" flow).
_time_requests: list[dict] = []

# Parent-fix — realtime side-effect queue (mock-only). After a child POSTs
# `/rest/v1/time_requests`, a daemon thread appends a `request_created`
# entry here so the parent's realtime channel can replay it within
# seconds. Keyed by parent_id so a future multi-parent mock extension
# stays isolated. Tests poke this queue directly without starting the
# HTTP server (see `test_projections.RequestCreatedSideEffectTest`).
_request_events: dict[str, list[dict]] = {}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _now_iso() -> str:
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _fanout_request_created(parent_id: str, request_id: str) -> None:
    """Parent-fix — fire-and-forget side effect that mirrors what
    production Supabase Realtime would push on the
    `device:{deviceId}:requests` channel when a new time request lands.
    Runs on a daemon thread so the POST handler's response is NOT
    delayed by the simulated push (test asserts this in
    `RequestCreatedSideEffectTest.test_post_time_requests_response_is_not_delayed_by_side_effect`).

    The log line is the production-shape observability surface; the
    in-process queue is a test-only seam that lives only on the mock.
    """
    def _emit() -> None:
        print(
            f"[realtime] request_created parent_id={parent_id} "
            f"request_id={request_id}",
            flush=True,
        )
        with _lock:
            _request_events.setdefault(parent_id, []).append(
                {"event": "request_created", "parent_id": parent_id, "request_id": request_id}
            )

    threading.Thread(target=_emit, daemon=True).start()


def _generate_code(length: int = 8) -> str:
    """8-char code using only unambiguous uppercase + digits — matches the
    input field on the child side which only accepts `[A-Z0-9]`."""
    alphabet = string.ascii_uppercase + string.digits
    # Avoid the confusable chars O/0/I/1 for human-readability — the static
    # fixture `ABCDEFGH` doesn't, but for new codes we filter for sanity.
    alphabet = alphabet.translate(str.maketrans("", "", "OI"))
    return "".join(secrets.choice(alphabet) for _ in range(length))


def _read_json_body(handler: http.server.BaseHTTPRequestHandler) -> dict:
    length = int(handler.headers.get("Content-Length", "0") or 0)
    if length <= 0:
        return {}
    raw = handler.rfile.read(length).decode("utf-8")
    if not raw.strip():
        return {}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {}


def _send_json(
    handler: http.server.BaseHTTPRequestHandler,
    status: HTTPStatus,
    body: dict | list,
) -> None:
    payload = json.dumps(body).encode("utf-8")
    handler.send_response(int(status))
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(payload)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(payload)


# ---------------------------------------------------------------------------
# Request handlers
# ---------------------------------------------------------------------------


def handle_auth_signup(handler: http.server.BaseHTTPRequestHandler, _body: dict) -> None:
    """`POST /auth/v1/signup` — official Supabase anonymous-auth entry
    point per the current docs. Empty body returns a real
    AnonAccessTokenResponse. The returned `user.id` is the agent's
    real auth.users UUID; the production pairing edge function will
    pair the agent device against this UUID (no second auth user is
    created — `enable_anonymous_sign_ins=true` must be set on the
    Supabase project).

    Mirrors the legacy `/auth/v1/token` shape so the Android client
    can use a single response parser for both flows.
    """
    token = "anon-" + secrets.token_urlsafe(16)
    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "access_token": token,
            "refresh_token": "refresh-" + secrets.token_urlsafe(8),
            "expires_in": 3600,
            "expires_at": int(_dt.datetime.now().timestamp()) + 3600,
            "user": {
                # The agent's real Supabase auth.users UUID (NOT the
                # parent's). R1.4 pins the pairing edge function to
                # honour this when authorizing the device insert.
                "id": "00000000-0000-0000-0000-000000000099",
                "email": "anonymous@placeholder.local",
                "app_metadata": {"device_id": "shared-mock-" + secrets.token_hex(4)},
            },
        },
    )


def handle_auth_token(handler: http.server.BaseHTTPRequestHandler, _body: dict) -> None:
    """`POST /auth/v1/token` — fallback for callers using the legacy
    password-grant flow (the deprecated path the Android client no
    longer issues; kept for compat with any leftover supabase-js
    refresh-token callers). Returns the same envelope as
    `/auth/v1/signup`."""
    handle_auth_signup(handler, _body)


def handle_magiclink(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /auth/v1/magiclink` — Supabase magic-link dispatch.

    The real backend would email a one-time link to `body["email"]`. We
    have no SMTP, so we just return a 200 OK with a synthetic
    `message_id`. The client (Android) treats this as "email was
    dispatched" and waits for the user to confirm via the in-app
    auto-detect / manual paste of the token_hash — which never arrives
    in this mock, so the parent flow stops at "check your inbox" unless
    the test harness drives the verify call directly with a fake
    token_hash (see `handle_verify_magiclink`).
    """
    message_id = "mock-msg-" + secrets.token_hex(8)
    _send_json(
        handler,
        HTTPStatus.OK,
        {"message_id": message_id},
    )


def handle_verify_magiclink(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /auth/v1/verify?type=magiclink` — exchanges a (fake)
    `token_hash` for a parent session.

    Real Supabase would verify the token_hash against the magic-link
    email it just sent. We skip that step and grant the session
    unconditionally — every (email, token_hash) pair is accepted. The
    returned `user.id` is the fixed PARENT_ID so subsequent
    `Authorization: Bearer …` calls are correlated.
    """
    token = "magiclink-" + secrets.token_urlsafe(16)
    email = (body.get("email") or "anonymous@placeholder.local").strip()
    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "access_token": token,
            "refresh_token": "refresh-" + secrets.token_urlsafe(8),
            "expires_in": 3600,
            "expires_at": int(_dt.datetime.now().timestamp()) + 3600,
            "user": {
                "id": PARENT_ID,
                "email": email,
                "app_metadata": {"device_id": "shared-mock-" + secrets.token_hex(4)},
            },
        },
    )


def handle_dev_login(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /auth/v1/dev-login` — dev-only shortcut that combines
    `magiclink` + `verify` in one call.

    Real Supabase needs an email round-trip:
      POST /auth/v1/magiclink → email with link → user clicks
      → POST /auth/v1/verify?type=magiclink → session.

    The shared mock has no SMTP. `dev-login` lets the Android client
    skip the round-trip entirely during cross-device pairing tests:
    send `{email}` once, get a session back. The returned `user.id` is
    the fixed PARENT_ID so subsequent `Authorization: Bearer …` calls
    are correlated with the parent's children list.

    Only enabled in shared-mock mode. Real Supabase rejects this path.
    """
    token = "devlogin-" + secrets.token_urlsafe(16)
    email = (body.get("email") or "anonymous@placeholder.local").strip()
    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "access_token": token,
            "refresh_token": "refresh-" + secrets.token_urlsafe(8),
            "expires_in": 3600,
            "expires_at": int(_dt.datetime.now().timestamp()) + 3600,
            "user": {
                "id": PARENT_ID,
                "email": email,
                "app_metadata": {"device_id": "shared-mock-" + secrets.token_hex(4)},
            },
        },
    )


def handle_create_pairing_code(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /functions/v1/create-pairing-code` — parent generates an 8-char
    code. Body: `{"device_name":"…","age_band":"…","ttl_minutes":N}`."""
    device_name = (body.get("device_name") or "Sin nombre").strip()
    age_band = body.get("age_band") or "7-12"
    ttl_minutes = int(body.get("ttl_minutes") or 10)

    code = _generate_code(8)
    with _lock:
        _pairing_codes[code] = {
            "parent_id": PARENT_ID,
            "device_name": device_name,
            "age_band": age_band,
            "created_at": _now_iso(),
            "ttl_minutes": ttl_minutes,
        }

    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "code": code,
            "expires_at": _dt.datetime.now(_dt.timezone.utc).isoformat(),
            "deeplink": f"parentalcontrol://pair?code={code}",
        },
    )


def handle_pairing(handler: http.server.BaseHTTPRequestHandler, body: dict) -> None:
    """`POST /functions/v1/pairing` — child consumes a code. Body:
    `{"code":"ABCDEFGH","device_name":"…","device_model":"…",
       "os_version":"…","app_version":"…"}`.

    On success returns `{device_id, parent_id}` and registers the child
    device in `_devices` so the parent will see it on next
    `get-devices-for-parent` call."""
    code = (body.get("code") or "").strip().upper()
    child_device_name = (body.get("device_name") or "Hijo").strip()
    device_model = body.get("device_model") or "unknown"
    os_version = str(body.get("os_version") or "0")
    app_version = body.get("app_version") or "1.0.0"

    with _lock:
        entry = _pairing_codes.pop(code, None)
        if entry is None:
            _send_json(
                handler,
                HTTPStatus.NOT_FOUND,
                {"error": f"unknown or already-consumed code '{code}'"},
            )
            return

        # Use the device_name the parent typed at code-creation time (the
        # parent's intent) — the child doesn't necessarily know its own name
        # yet at this point in the flow.
        device_id = "device-child-" + secrets.token_hex(4)
        _devices.append(
            {
                "id": device_id,
                "device_name": entry["device_name"],
                "device_model": device_model,
                "os_version": os_version,
                "app_version": app_version,
                "device_state": "ACTIVE",
                "policy_version": 1,
                "last_seen_at": _now_iso(),
                "parent_id": entry["parent_id"],
                "_child_reported_name": child_device_name,
            }
        )

    _send_json(
        handler,
        HTTPStatus.OK,
        {"device_id": device_id, "parent_id": entry["parent_id"]},
    )


def _public_device(d: dict) -> dict:
    """Pure projection from an internal `_devices` row to the wire shape
    `get-devices-for-parent` returns.

    Strips underscore-prefixed helper keys (server-side bookkeeping).
    The production query explicitly selects `child_id`, so the
    top-level `child_id` key MUST always appear on the wire — with
    `null` value on rows that have no children-table join. The nested
    `child: {id, first_name}` is set when both `child_id` and
    `_child_first_name` are present; otherwise it is `null`.
    """
    child_id = d.get("child_id")
    child_name = d.get("_child_first_name")
    row = {k: v for k, v in d.items() if not k.startswith("_")}
    # Ensure the selected `child_id` column is present on every row,
    # even when null — PostgREST emits `null` for selected columns that
    # have no value, never omits the key.
    row.setdefault("child_id", None)
    if child_id is not None and child_name is not None:
        row["child"] = {"id": child_id, "first_name": child_name}
    else:
        row["child"] = None
    return row


def _public_time_request(row: dict, device_by_id: dict) -> dict:
    """Pure projection to wire shape `select=*,devices(device_name)`.
    Adds `devices: {device_name}` for known device ids, `devices: null`
    for orphaned rows. The flat `device_name` column is never emitted.
    """
    out = dict(row)
    device = device_by_id.get(row.get("device_id") or "")
    if device is not None:
        out["devices"] = {"device_name": device["device_name"]}
    else:
        out["devices"] = None
    return out


def handle_get_devices(handler: http.server.BaseHTTPRequestHandler) -> None:
    """`GET /functions/v1/get-devices-for-parent` — returns the list of
    devices paired with the parent. We project each row through
    `_public_device` so the wire shape mirrors production
    (`child_id` always present, `child` nested object/null)."""
    with _lock:
        public = [_public_device(d) for d in _devices]
    _send_json(handler, HTTPStatus.OK, public)


def handle_get_policy(handler: http.server.BaseHTTPRequestHandler) -> None:
    """R4 — `GET /functions/v1/get-policy` — child reads the live policy
    + device_state for its own row. The Android client pulls this on
    a deterministic lifecycle (startup / foreground / manual
    refresh, per the WU-2d residual note). The response carries
    the up-to-date `device_state` so the parent's lock/unlock
    propagates to the child device on the next sync cycle
    (NextSync; FCM push is OUT OF SCOPE).
    """
    with _lock:
        # The mock has at most one device for the agent. We pick the
        # most recently updated row so lock propagation follows the
        # `set-device-state` timeline.
        devices_sorted = sorted(
            _devices,
            key=lambda d: d.get("updated_at") or d.get("last_seen_at") or "",
            reverse=True,
        )
        target = devices_sorted[0] if devices_sorted else None

    if target is None:
        _send_json(handler, HTTPStatus.OK, [])
        return

    _send_json(
        handler,
        HTTPStatus.OK,
        [
            {
                "policy_id": f"policy-{target['id']}",
                "version": target.get("policy_version", 1),
                "category_assignments": {},
                "app_policies": [],
                "device_state": target.get("device_state", "ACTIVE"),
                "server_time": int(_dt.datetime.now().timestamp()),
            }
        ],
    )


def handle_get_templates(handler: http.server.BaseHTTPRequestHandler) -> None:
    """Empty template list — the app handles `[]` fine."""
    _send_json(handler, HTTPStatus.OK, [])


def handle_get_time_requests(handler: http.server.BaseHTTPRequestHandler) -> None:
    """`GET /rest/v1/time_requests` — pending requests.

    Honors the client's `status=eq.PENDING` / `status=in.(...)` and
    `device_id=eq.X` filters from the query string so resolved requests
    (APPROVED/DENIED) stop appearing in the parent's Solicitudes list once
    they take action, and the child can pull only its own approved grants
    on sync. Real Supabase/PostgREST applies the filters at the database
    level; we mirror that here so the mock behaves like the production
    backend would."""
    qs = parse_qs(urlparse(handler.path).query)
    status_filters = qs.get("status", [])
    device_filters = qs.get("device_id", [])

    with _lock:
        rows = list(_time_requests)
        device_by_id = {d["id"]: d for d in _devices}

    # `device_id=eq.X` → only rows owned by X. Multiple device filters are
    # OR'd (rare; covers `in.(a,b)` shape too if the client ever uses it).
    if device_filters:
        allowed_devices: set[str] = set()
        for f in device_filters:
            if f.startswith("in.(") and f.endswith(")"):
                allowed_devices.update(v.strip() for v in f[len("in.(") : -1].split(","))
            elif f.startswith("eq."):
                allowed_devices.add(f[len("eq.") :])
            else:
                allowed_devices.add(f)
        rows = [r for r in rows if r.get("device_id") in allowed_devices]

    if status_filters:
        keep: list[dict] = []
        for f in status_filters:
            # PostgREST operators: `eq.PENDING` (single) or
            # `in.(PENDING,APPROVED)` (any-of). We only need the two shapes the
            # Android client actually emits.
            if f.startswith("in.(") and f.endswith(")"):
                allowed = {v.strip() for v in f[len("in.(") : -1].split(",")}
            elif f.startswith("eq."):
                allowed = {f[len("eq.") :]}
            else:
                allowed = {f}
            for r in rows:
                if r.get("status") in allowed:
                    keep.append(r)
        seen: set[str] = set()
        public = []
        for r in keep:
            if r["id"] not in seen:
                seen.add(r["id"])
                public.append(_public_time_request(r, device_by_id))
    else:
        public = [_public_time_request(r, device_by_id) for r in rows]
    _send_json(handler, HTTPStatus.OK, public)


def handle_post_time_requests(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /rest/v1/time_requests` — child asks for more time. Echoes the
    inserted row back. Field name `minutes_requested` matches the production
    Postgres schema (`supabase/migrations/001_initial_schema.sql`) and the
    client DTO in `ParentRepository.TimeRequestDto`.

    Parent-fix — after appending the row, fan out a non-blocking
    `request_created` event so the parent sees the request within seconds
    (was: up to 5 min via `SolicitudesPollingWorker`). The fan-out runs
    on a daemon thread so the child's optimistic POST response is not
    delayed; see `_fanout_request_created`."""
    row = {
        "id": "req-" + secrets.token_hex(4),
        "device_id": body.get("device_id") or "unknown",
        "minutes_requested": int(body.get("minutes_requested") or 0),
        "reason": body.get("reason"),
        "status": "PENDING",
        "created_at": _now_iso(),
        "parent_id": PARENT_ID,
    }
    with _lock:
        _time_requests.append(row)
    # Resolve the parent for the realtime side-effect. Single-parent
    # mock keeps it constant, but we derive from the device row when
    # available so a future multi-parent mock stays self-consistent.
    with _lock:
        device = next((d for d in _devices if d["id"] == row["device_id"]), None)
        parent_id = device["parent_id"] if device else PARENT_ID
    _fanout_request_created(parent_id, row["id"])
    _send_json(handler, HTTPStatus.CREATED, [row])


def _resolve_time_request(body: dict) -> dict | None:
    """Find a pending row by `id` (or `request_id`) and return a snapshot
    copy. Caller is responsible for mutating it and re-emitting."""
    target_id = body.get("request_id") or body.get("id") or ""
    with _lock:
        for row in _time_requests:
            if row["id"] == target_id:
                return dict(row)
    return None


def handle_resolve_request(
    handler: http.server.BaseHTTPRequestHandler,
    body: dict,
    status: str,
) -> None:
    """Shared approve/deny path. Sets the matching row's status and
    `resolved_at` timestamp, then echoes the row back so the parent UI can
    drop it from the Solicitudes list on its next poll."""
    row = _resolve_time_request(body)
    if row is None:
        _send_json(
            handler,
            HTTPStatus.NOT_FOUND,
            {"error": f"unknown time_request id={body.get('request_id') or body.get('id')!r}"},
        )
        return

    row["status"] = status
    row["resolved_at"] = _now_iso()
    if status == "APPROVED":
        # Record the minutes the parent actually granted (defaults to whatever
        # the child asked for, in case the client omits `minutes_approved`).
        row["minutes_approved"] = int(
            body.get("minutes_approved") or row.get("minutes_requested") or 0
        )

    with _lock:
        for i, existing in enumerate(_time_requests):
            if existing["id"] == row["id"]:
                _time_requests[i] = row
                break

    _send_json(handler, HTTPStatus.OK, row)


def handle_approve_request(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /functions/v1/approve-request` — parent approves a pending
    request. Body: `{"request_id": "req-…", "minutes_approved": N, "action":
    "APPROVE"|"DENY"}`.

    The Android client intentionally reuses this single endpoint for both
    Aprobar and Denegar (see KDoc on `ParentRepository.denyRequest`,
    `data/repository/ParentRepository.kt:268`). The wire format is locked —
    we branch on the `action` field here. The production edge function at
    `supabase/functions/approve-request/index.ts:38` is tracked to grow the
    same branch; this mock just stays one step ahead so end-to-end tests can
    distinguish APPROVED vs DENIED rows today."""
    action = (body.get("action") or "APPROVE").upper()
    if action == "DENY":
        handle_resolve_request(handler, body, "DENIED")
    else:
        handle_resolve_request(handler, body, "APPROVED")


def handle_deny_request(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """`POST /functions/v1/deny-request` — parent denies a pending
    request. Body: `{"request_id": "req-…"}`."""
    handle_resolve_request(handler, body, "DENIED")


def handle_set_device_state(
    handler: http.server.BaseHTTPRequestHandler, body: dict
) -> None:
    """WU-2 — `POST /functions/v1/set-device-state`. Mirrors the
    production edge function at
    `supabase/functions/set-device-state/index.ts`. Locks or unlocks
    a device by mutating its `device_state` + bumping `policy_version`
    by 1. Echoes a Supabase-auth-shape envelope so the Android
    `ParentRepository.lockDevice` / `unlockDevice` paths consume it
    unchanged.

    Body: `{"device_id":"<uuid>", "state":"LOCKED"|"ACTIVE"}`.

    The mock ownership check accepts any device whose seeded or post-
    pairing `parent_id` matches the validated parent's UUID. For tests
    that don't seed via the existing fixtures, the mock also allows
    any device_id and falls back to "owned" — production's stricter
    policy is exercised by the Deno edge-function tests in
    `supabase/functions/set-device-state/index_test.ts`."""
    auth = handler.headers.get("Authorization")
    if not auth:
        _send_json(
            handler,
            HTTPStatus.UNAUTHORIZED,
            {"error": "Token requerido"},
        )
        return

    device_id = body.get("device_id")
    state_raw = (body.get("state") or "").upper()
    if not device_id:
        _send_json(
            handler,
            HTTPStatus.UNPROCESSABLE_ENTITY,
            {"error": "device_id es requerido"},
        )
        return
    if state_raw not in ("LOCKED", "ACTIVE"):
        _send_json(
            handler,
            HTTPStatus.UNPROCESSABLE_ENTITY,
            {"error": "state debe ser uno de LOCKED, ACTIVE"},
        )
        return

    with _lock:
        target = next((d for d in _devices if d["id"] == device_id), None)
        if target is None:
            # F2c — production RLS rejects unknown device_ids with 404
            # (the row is not visible to the parent since RLS scopes
            # `parent_id = auth.uid()`). The mock mirrors that 404
            # rather than auto-creating a row. Parents that want to
            # lock a freshly-paired device must first observe it via
            # `get-devices-for-parent` (which auto-creates the device
            # row during the pairing completion path) and THEN call
            # `set-device-state`. Auto-creating here was masking
            # client-side validation bugs.
            _send_json(
                handler,
                HTTPStatus.NOT_FOUND,
                {"error": f"device_id no encontrado: {device_id}"},
            )
            return

        target["device_state"] = state_raw
        target["policy_version"] = int(target.get("policy_version", 0)) + 1
        target["updated_at"] = _now_iso()
        snapshot = dict(target)

    _send_json(
        handler,
        HTTPStatus.OK,
        {
            "success": True,
            "device_id": snapshot["id"],
            "state": snapshot["device_state"],
            "policy_version": snapshot["policy_version"],
            "updated_at": snapshot.get("updated_at"),
        },
    )


# ---------------------------------------------------------------------------
# HTTP server glue
# ---------------------------------------------------------------------------


class _Handler(http.server.BaseHTTPRequestHandler):
    # Silence the default access log; we print our own one-liners below.
    def log_message(self, _format: str, *_args: object) -> None:  # noqa: A003
        return

    def _route(self, method: str) -> None:
        path = urlparse(self.path).path
        query = urlparse(self.path).query
        body = _read_json_body(self) if method == "POST" else {}

        with _lock:
            n_codes = len(_pairing_codes)
            n_devices = len(_devices)
        qs = f" ?{query}" if query else ""
        print(
            f"[{_now_iso()}] {method} {path}{qs}  "
            f"(codes={n_codes}, devices={n_devices})",
            flush=True,
        )

        if method == "POST" and path.endswith("/auth/v1/signup"):
            handle_auth_signup(self, body)
        elif method == "POST" and path.endswith("/auth/v1/token"):
            handle_auth_token(self, body)
        elif method == "POST" and path.endswith("/auth/v1/magiclink"):
            handle_magiclink(self, body)
        elif method == "POST" and path.endswith("/auth/v1/verify"):
            handle_verify_magiclink(self, body)
        elif method == "POST" and path.endswith("/auth/v1/dev-login"):
            handle_dev_login(self, body)
        elif method == "POST" and path.endswith("/functions/v1/create-pairing-code"):
            handle_create_pairing_code(self, body)
        elif method == "POST" and path.endswith("/functions/v1/pairing"):
            handle_pairing(self, body)
        elif (
            method == "POST" and path.endswith("/functions/v1/set-device-state")
        ):
            handle_set_device_state(self, body)
        # Read endpoints accept both GET (HTTP-spec-correct) and POST
        # (which the current Android client uses — see
        # `ParentRepository.getDevices` calling `httpClient.post(...)`).
        # The real Supabase edge function accepts both, so we mirror that.
        elif path.endswith("/functions/v1/get-devices-for-parent") and method in ("GET", "POST"):
            handle_get_devices(self)
        elif path.endswith("/functions/v1/get-policy") and method == "GET":
            handle_get_policy(self)
        elif (
            path.endswith("/functions/v1/get-templates")
            or path.endswith("/rest/v1/templates")
        ) and method in ("GET", "POST"):
            handle_get_templates(self)
        elif path.startswith("/rest/v1/time_requests") and method == "GET":
            handle_get_time_requests(self)
        elif path.startswith("/rest/v1/time_requests") and method == "POST":
            handle_post_time_requests(self, body)
        elif method == "POST" and path.endswith("/functions/v1/approve-request"):
            handle_approve_request(self, body)
        elif method == "POST" and path.endswith("/functions/v1/deny-request"):
            handle_deny_request(self, body)
        else:
            _send_json(
                self,
                HTTPStatus.NOT_FOUND,
                {"error": f"unknown route {method} {path}"},
            )

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        self._route("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._route("POST")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    parser.add_argument("--host", default="0.0.0.0", help="bind host (default 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8787, help="bind port (default 8787)")
    args = parser.parse_args()

    server = http.server.ThreadingHTTPServer((args.host, args.port), _Handler)
    print(
        f"shared-mock-server listening on http://{args.host}:{args.port}\n"
        f"  parent_id (fixed) = {PARENT_ID}\n"
        f"  state: 0 codes, 0 devices",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nshutting down", flush=True)
        server.server_close()


if __name__ == "__main__":
    main()