#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
OUT_DIR="${OUT_DIR:-/tmp/mcp_scenario_results}"
mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*.body "$OUT_DIR"/summary.tsv
printf 'scenario\texpected\tactual\tresult\tnote\n' > "$OUT_DIR/summary.tsv"

record() {
  local name="$1" expected="$2" actual="$3" result="$4" note="$5"
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$expected" "$actual" "$result" "$note" >> "$OUT_DIR/summary.tsv"
}

request() {
  local name="$1" expected="$2" method="$3" path="$4" body="$5" token="${6:-}" form_file="${7:-}"
  local output="$OUT_DIR/${name}.body" status
  local -a args=(-sS -o "$output" -w '%{http_code}' --max-time 15 -X "$method" "${BASE_URL}${path}")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  [[ -n "$form_file" ]] && args+=(-F "file=@${form_file};type=text/csv")
  status=$(curl "${args[@]}" || true)
  local result="PASS"; [[ "$status" == "$expected" ]] || result="FAIL"
  record "$name" "$expected" "$status" "$result" "$(tr '\n' ' ' < "$output" | head -c 220)"
  printf '%s' "$status"
}

request health 200 GET /api/health ''
request unauthenticated_config 403 GET /api/v1/ai/config ''
request invalid_login 401 POST /api/auth/login '{"username":"admin","password":"wrong-password"}'
# Read from the environment, never embedded: the admin password is whatever
# MCP_DEFAULT_PASSWORD is set to, or the value the backend logged on first start.
: "${MCP_DEFAULT_PASSWORD:?set MCP_DEFAULT_PASSWORD to the admin password before running this}"
request valid_login 200 POST /api/auth/login "{\"username\":\"admin\",\"password\":\"${MCP_DEFAULT_PASSWORD}\"}"
TOKEN=$(node -e "const fs=require('fs'); console.log(JSON.parse(fs.readFileSync('$OUT_DIR/valid_login.body','utf8')).token || '')")
if [[ -z "$TOKEN" ]]; then
  record token_obtained nonempty empty FAIL 'Cannot execute authenticated scenarios without a token'
  cat "$OUT_DIR/summary.tsv"
  exit 1
fi
record token_obtained nonempty nonempty PASS 'Administrator JWT obtained'

request authenticated_config 200 GET /api/v1/ai/config '' "$TOKEN"
request ollama_model_discovery 200 GET '/api/v1/ai/config/ollama-models?url=http%3A%2F%2Flocalhost%3A11434' '' "$TOKEN"

cat > "$OUT_DIR/freshservice_basic.csv" <<'CSV'
id,subject,description,priority,category
FS-E2E-1001,Store printer offline,Printer 7 is offline with a stuck print queue,High,Hardware
CSV
request freshservice_basic_import 200 POST '/api/v1/intake/incidents/import?sourceSystem=Freshservice' '' "$TOKEN" "$OUT_DIR/freshservice_basic.csv"
request freshservice_duplicate_import 200 POST '/api/v1/intake/incidents/import?sourceSystem=Freshservice' '' "$TOKEN" "$OUT_DIR/freshservice_basic.csv"

cat > "$OUT_DIR/freshservice_invalid.csv" <<'CSV'
id,subject,description,priority
FS-E2E-1003,,Missing subject,High
CSV
request freshservice_invalid_row 200 POST '/api/v1/intake/incidents/import?sourceSystem=Freshservice' '' "$TOKEN" "$OUT_DIR/freshservice_invalid.csv"

cat > "$OUT_DIR/freshservice_quoted.csv" <<'CSV'
id,subject,description,priority,category
FS-E2E-1002,Printer message,"Paper jam, tray two needs inspection",High,Hardware
CSV
request freshservice_quoted_import 200 POST '/api/v1/intake/incidents/import?sourceSystem=Freshservice' '' "$TOKEN" "$OUT_DIR/freshservice_quoted.csv"

cat > "$OUT_DIR/servicenow_numeric.csv" <<'CSV'
number,short description,description,priority,assignment group,configuration item
INC-E2E-2001,Store VPN unavailable,Store 4 VPN session cannot connect,2,Network Team,store-004-vpn
CSV
request servicenow_numeric_import 200 POST '/api/v1/intake/incidents/import?sourceSystem=ServiceNow' '' "$TOKEN" "$OUT_DIR/servicenow_numeric.csv"

request incident_list 200 GET /api/v1/incidents '' "$TOKEN"
node - <<NODE
const fs = require('fs');
const out = '$OUT_DIR';
const incidents = JSON.parse(fs.readFileSync(out + '/incident_list.body', 'utf8'));
const get = id => incidents.find(x => x.externalId === id) || {};
const quoted = get('FS-E2E-1002');
const snow = get('INC-E2E-2001');
const primary = get('FS-E2E-1001');
fs.writeFileSync(out + '/ids.json', JSON.stringify({ primaryId: primary.id || '', quoted, snow }, null, 2));
function append(name, expected, actual, result, note) { fs.appendFileSync(out + '/summary.tsv', [name, expected, actual, result, note].join('\t') + '\n'); }
append('quoted_csv_preserves_description', 'Paper jam, tray two needs inspection', quoted.description || '', quoted.description === 'Paper jam, tray two needs inspection' ? 'PASS' : 'FAIL', 'Quoted comma handling');
append('quoted_csv_maps_high_priority', 'P2', quoted.priority || '', quoted.priority === 'P2' ? 'PASS' : 'FAIL', 'Freshservice High priority mapping');
append('servicenow_numeric_priority_mapping', 'P2', snow.priority || '', snow.priority === 'P2' ? 'PASS' : 'FAIL', 'ServiceNow numeric priority 2 mapping');
NODE
PRIMARY_ID=$(node -e "console.log(require('$OUT_DIR/ids.json').primaryId || '')")
if [[ -n "$PRIMARY_ID" ]]; then
  request create_plan_without_rag 200 POST "/api/v1/hitl/incidents/${PRIMARY_ID}/plan" '' "$TOKEN"
  node - <<NODE
const fs = require('fs'); const out = '$OUT_DIR'; const p = JSON.parse(fs.readFileSync(out + '/create_plan_without_rag.body', 'utf8'));
const status = p.plan?.guardrailStatus || ''; const evidence = p.plan?.sopEvidence || '';
fs.appendFileSync(out + '/summary.tsv', ['no_sop_plan_must_block','BLOCK',status,status === 'BLOCK' ? 'PASS':'FAIL',evidence.replace(/\s+/g,' ').slice(0,180)].join('\t')+'\n');
fs.writeFileSync(out + '/plan.json', JSON.stringify(p, null, 2));
NODE
  REQUEST_ID=$(node -e "console.log(require('$OUT_DIR/plan.json').hitlRequest?.id || '')")
  if [[ -n "$REQUEST_ID" ]]; then
    request approve_no_sop_plan 409 POST "/api/v1/hitl/requests/${REQUEST_ID}/decision" '{"decision":"APPROVE","reason":"Scenario test"}' "$TOKEN"
    request dryrun_no_sop_plan 409 POST "/api/v1/hitl/requests/${REQUEST_ID}/dry-run" '' "$TOKEN"
  else
    record no_sop_approval_request absent absent PASS 'No request was created for an unavailable-SOP plan'
  fi
fi

printf '%s\n' '--- Scenario Summary ---'
column -t -s $'\t' "$OUT_DIR/summary.tsv" 2>/dev/null || cat "$OUT_DIR/summary.tsv"
printf '\nArtifacts: %s\n' "$OUT_DIR"
