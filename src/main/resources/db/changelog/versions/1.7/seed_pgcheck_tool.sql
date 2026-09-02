-- Seed the PostgreSQL Service Check tool into tools.saved_scripts.
-- The row matches the JSON shape POST /api/v1/scripts/generate would emit, so
-- the LLM-generated artifact and the seeded artifact are interchangeable from
-- the runtime's point of view.
--
-- Idempotent: ON CONFLICT (id) DO NOTHING, with a fixed UUID so re-runs and
-- re-imports converge on the same row.
--
-- The fixed UUID (a07d0c1e-...-8b3f) is the tool's identity; the LLM version
-- is recorded in description so future regenerations can be diffed.

INSERT INTO tools.saved_scripts (
    id,
    name,
    description,
    script_content,
    language,
    category,
    target_host,
    required_input_data,
    validated_in_dry_run,
    created_at,
    updated_at
) VALUES (
    'a07d0c1e-5b1f-4a3e-9d6c-2f8b3f0a8b3f',
    'PostgreSQL Service Check',
    'Read-only probe that reports whether PostgreSQL is installed, whether its service unit is active, and whether the host:port actually accepts connections. Use during database-related incidents to triage in one call whether the problem is the server, the service, or the network. v1.0.0 (seeded).',
    E'#!/usr/bin/env python3\n"""Read-only PostgreSQL health probe for incident triage.\n\nReports, in order:\n  1. Whether `psql` / `pg_isready` / `systemctl` exist on this host (capability).\n  2. Whether the named systemd unit is active (service state).\n  3. Whether host:port accepts a real TCP connection (network / listener).\n  4. Whether PostgreSQL is ready to serve queries (readiness).\n\nExits 0 only when every check above passes. Exits 1 on any failure, with a\nmachine-readable JSON line on stdout for the agent and a human line on stderr.\n"""\n\nfrom __future__ import annotations\n\nimport json\nimport re\nimport shutil\nimport socket\nimport subprocess\nimport sys\n\nHOST_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9.\\-]{0,253}$")\nSERVICE_RE = re.compile(r"^[a-zA-Z0-9_.@-]{1,64}$")\n\n\ndef _validate(host: str, port: int, service: str) -> str | None:\n    if not HOST_RE.match(host):\n        return f"invalid host: {host!r}"\n    if not (1 <= port <= 65535):\n        return f"invalid port: {port}"\n    if not SERVICE_RE.match(service):\n        return f"invalid service name: {service!r}"\n    return None\n\n\ndef _which(name: str) -> str | None:\n    return shutil.which(name)\n\n\ndef _run(args, timeout=5):\n    try:\n        proc = subprocess.run(\n            args,\n            shell=False,\n            check=False,\n            capture_output=True,\n            text=True,\n            timeout=timeout,\n        )\n    except subprocess.TimeoutExpired:\n        return 124, "", f"timeout after {timeout}s"\n    except FileNotFoundError as e:\n        return 127, "", f"not found: {e.filename}"\n    return proc.returncode, proc.stdout.strip(), proc.stderr.strip()\n\n\ndef _unit_active(service: str):\n    systemctl = _which("systemctl")\n    if systemctl is None:\n        return False, "systemctl not available on this host"\n    code, out, err = _run([systemctl, "is-active", service])\n    if code == 0 and out == "active":\n        return True, "active"\n    if code == 0:\n        return False, f"not active (state={out!r})"\n    return False, err or f"systemctl exited {code}"\n\n\ndef _pg_ready(host: str, port: int):\n    pg_isready = _which("pg_isready")\n    if pg_isready is None:\n        return False, "pg_isready not installed"\n    code, out, err = _run([pg_isready, "-h", host, "-p", str(port), "-t", "5"])\n    if code == 0:\n        return True, out or "accepting connections"\n    meaning = {1: "rejecting connections", 2: "no response", 3: "no attempt"}.get(code, f"exit {code}")\n    return False, f"{meaning}: {err or out}"\n\n\ndef _tcp_open(host: str, port: int):\n    try:\n        with socket.create_connection((host, port), timeout=3):\n            return True, "tcp open"\n    except socket.timeout:\n        return False, "tcp timeout"\n    except OSError as e:\n        return False, f"tcp {e.strerror.lower()}"\n\n\ndef main() -> int:\n    host = sys.argv[1] if len(sys.argv) > 1 else "localhost"\n    try:\n        port = int(sys.argv[2]) if len(sys.argv) > 2 else 5432\n    except ValueError:\n        print(json.dumps({"ok": False, "stage": "validate", "error": "port must be an integer"}))\n        return 1\n    service = sys.argv[3] if len(sys.argv) > 3 else "postgresql"\n\n    err = _validate(host, port, service)\n    if err:\n        print(json.dumps({"ok": False, "stage": "validate", "error": err}))\n        return 1\n\n    unit_ok, unit_detail = _unit_active(service)\n    tcp_ok, tcp_detail = _tcp_open(host, port)\n    if tcp_ok:\n        ready_ok, ready_detail = _pg_ready(host, port)\n    else:\n        ready_ok, ready_detail = False, "skipped (no listener)"\n\n    ok = unit_ok and tcp_ok and ready_ok\n    result = {\n        "ok": ok,\n        "host": host,\n        "port": port,\n        "service": service,\n        "unit_active": unit_ok,\n        "unit_detail": unit_detail,\n        "tcp_open": tcp_ok,\n        "tcp_detail": tcp_detail,\n        "ready": ready_ok,\n        "ready_detail": ready_detail,\n        "summary": (\n            "healthy" if ok else\n            "unit down" if not unit_ok else\n            "port closed" if not tcp_ok else\n            "rejecting connections"\n        ),\n    }\n    print(json.dumps(result))\n    if not ok:\n        print(f"postgres {host}:{port} not healthy: {result[\"summary\"]}", file=sys.stderr)\n    return 0 if ok else 1\n\n\nif __name__ == "__main__":\n    sys.exit(main())\n',
    'python',
    'database_health',
    'localhost',
    'hostname (Required), port (Optional), service_name (Optional)',
    TRUE,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;
