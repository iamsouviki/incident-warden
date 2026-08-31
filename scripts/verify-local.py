import json
import sys
import time
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
login = request("/api/auth/login", "POST", {"username": "admin", "password": "michaels@1", "rememberMe": True})
assert login.get("accessToken") and login.get("refreshToken"), login
assert login["refreshExpiresIn"] == 7 * 24 * 60 * 60 * 1000, login
rotated = request("/api/auth/refresh", "POST", {"refreshToken": login["refreshToken"]})
assert rotated.get("accessToken") and rotated.get("refreshToken"), rotated
answer = request("/api/v1/rag/chat", "POST", {"question": "What is the capital of France?"}, login["accessToken"])
assert "only answer questions grounded" in answer["answer"].lower(), answer
telemetry = request("/api/v1/telemetry/events", "POST", {
    "deviceId": "pos-verify-01", "storeId": "store-verify", "deviceType": "POS",
    "eventType": "POS_OFFLINE", "severity": "HIGH", "message": "POS terminal is offline"
}, login["accessToken"])
assert telemetry.get("incidentId"), telemetry
for _ in range(8):
    time.sleep(1)
    traces = request("/api/v1/autonomy/traces?limit=20", token=login["accessToken"])
    if any(t.get("incidentId") == telemetry["incidentId"] and t.get("phase") == "POST_VALIDATE" for t in traces):
        break
assert any(t.get("incidentId") == telemetry["incidentId"] and t.get("validationStatus") == "PASS" for t in traces), traces
print(json.dumps({
    "health": health,
    "tokens": {"access": True, "refresh": True, "rotated": True},
    "chatbotGuardrail": "PASS",
    "telemetryAutonomy": "PASS",
}, indent=2))
