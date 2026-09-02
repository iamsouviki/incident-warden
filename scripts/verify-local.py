import json
import os
import urllib.request

BASE = "http://localhost:8080"

def request(path, method="GET", payload=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=10) as response:
        return json.loads(response.read().decode())

health = request("/api/health")
assert health["status"] == "UP", health
# The admin password comes from the environment, never from this file. On a fresh database it is
# the username ('admin'); after the forced first-sign-in change it is whatever the operator chose.
admin_password = os.environ.get("MCP_ADMIN_PASSWORD", "")
assert admin_password, "set MCP_ADMIN_PASSWORD to the admin password before running this"
login = request("/api/auth/login", "POST", {"username": "admin", "password": admin_password, "rememberMe": True})
assert login.get("token") and login.get("refreshToken"), login
assert login["refreshExpiresIn"] == 7 * 24 * 60 * 60 * 1000, login
rotated = request("/api/auth/refresh", "POST", {"refreshToken": login["refreshToken"]})
assert rotated.get("token") and rotated.get("refreshToken"), rotated
answer = request("/api/v1/rag/chat", "POST", {"question": "What is the capital of France?"}, login["token"])
assert "only answer questions grounded" in answer["answer"].lower(), answer
telemetry = request("/api/v1/telemetry/events", "POST", {
    "deviceId": "pos-verify-01", "storeId": "store-verify", "deviceType": "POS",
    "eventType": "POS_OFFLINE", "severity": "HIGH", "message": "POS terminal is offline"
}, login["token"])
assert telemetry.get("incidentId"), telemetry
# The property worth asserting is a negative: a HIGH-severity device event raises an incident and
# then nothing happens to it. This used to poll /api/v1/autonomy/traces for a POST_VALIDATE pass,
# back when telemetry could trigger a run on its own. That path is deleted, so the check is that
# the incident is still waiting for a person rather than closed by the platform.
incident = request(f"/api/v1/incidents/{telemetry['incidentId']}", token=login["token"])
assert incident.get("status") not in ("RESOLVED", "CLOSED"), incident
print(json.dumps({
    "health": health,
    "tokens": {"access": True, "refresh": True, "rotated": True},
    "chatbotGuardrail": "PASS",
    "telemetryRaisesNothingRuns": "PASS",
}, indent=2))
