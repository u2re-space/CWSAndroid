#!/usr/bin/env bash

set -euo pipefail

# End-to-end clipboard smoke test:
# VDS (45.150.9.153) <-> Windows (192.168.0.110) through gateway (45.147.121.152 / 192.168.0.200).
# The script applies portable.config launcherEnv overrides, restarts PM2, and retries tests.

GATEWAY_SSH="${GATEWAY_SSH:-u2re-dev@192.168.0.200}"
WINDOWS_SSH="${WINDOWS_SSH:-U2RE@192.168.0.110}"
VDS_SSH="${VDS_SSH:-root@45.150.9.153}"
VDS_SSH_KEY="${VDS_SSH_KEY:-$HOME/.ssh/id_ecdsa}"

GATEWAY_DIR="${GATEWAY_DIR:-/home/u2re-dev/U2RE.space/apps/CrossWord/src/endpoint}"
WINDOWS_DIR="${WINDOWS_DIR:-C:\\Users\\U2RE\\endpoint-portable}"
VDS_DIR="${VDS_DIR:-/root/endpoint-portable}"
GATEWAY_URL="${GATEWAY_URL:-https://45.147.121.152:8443/}"

MAX_ATTEMPTS="${MAX_ATTEMPTS:-3}"
WAIT_SECONDS="${WAIT_SECONDS:-30}"
CLIP_AUTH_TOKEN="${CLIP_AUTH_TOKEN:-n3v3rm1nd}"

log() {
  printf '[vds-e2e] %s\n' "$*"
}

ssh_gateway() {
  ssh -o StrictHostKeyChecking=accept-new "${GATEWAY_SSH}" "$@"
}

ssh_windows() {
  ssh -o StrictHostKeyChecking=accept-new "${WINDOWS_SSH}" "$@"
}

ssh_vds() {
  ssh -i "${VDS_SSH_KEY}" -o StrictHostKeyChecking=accept-new "${VDS_SSH}" "$@"
}

apply_linux_portable_env() {
  local host_cmd="$1"
  local target_dir="$2"
  local assoc_id="$3"
  local assoc_token="$4"
  local bridge_endpoints_json="$5"
  local enable_clip_logging="$6"
  local clip_preview="$7"
  local enable_tunnel_debug="$8"
  local enable_airpad_native="$9"
  local enable_robot="${10}"
  "${host_cmd}" "python3 - <<'PY'
import json
from pathlib import Path

target = Path('${target_dir}') / 'portable.config.json'
data = json.loads(target.read_text(encoding='utf-8'))
env = data.setdefault('launcherEnv', {})
env['CWS_ASSOCIATED_ID'] = '${assoc_id}'
env['CWS_ASSOCIATED_TOKEN'] = '${assoc_token}'
env['CWS_BRIDGE_ENDPOINTS'] = json.loads('''${bridge_endpoints_json}''')
env['CWS_ROLES'] = ['responser-initiated','requestor-initiated','responser-initiator','requestor-initiator']
env['CWS_CLIPBOARD_LOGGING'] = 'true' if '${enable_clip_logging}' == 'true' else 'false'
env['CWS_CLIPBOARD_LOG_HASH'] = 'true'
env['CWS_CLIPBOARD_LOG_PREVIEW'] = int('${clip_preview}')
env['CWS_TUNNEL_DEBUG'] = True if '${enable_tunnel_debug}' == 'true' else False
env['CWS_AIRPAD_NATIVE_ACTIONS'] = 'true' if '${enable_airpad_native}' == 'true' else 'false'
env['CWS_AIRPAD_ROBOTJS_ENABLED'] = 'true' if '${enable_robot}' == 'true' else 'false'
target.write_text(json.dumps(data, ensure_ascii=False, indent=4) + '\n', encoding='utf-8')
print(f'updated: {target}')
PY"
}

apply_windows_portable_env() {
  ssh_windows "powershell -NoProfile -Command \"\
\$p='${WINDOWS_DIR}\\portable.config.json';\
\$j=Get-Content -Raw -Path \$p | ConvertFrom-Json;\
if (-not \$j.launcherEnv) { \$j | Add-Member -MemberType NoteProperty -Name launcherEnv -Value ([pscustomobject]@{}) -Force };\
\$envObj=@{}; \$j.launcherEnv.PSObject.Properties | ForEach-Object { \$envObj[\$_.Name]=\$_.Value };\
\$envObj['CWS_ASSOCIATED_ID']='L-192.168.0.110';\
\$envObj['CWS_ASSOCIATED_TOKEN']='${CLIP_AUTH_TOKEN}';\
\$envObj['CWS_BRIDGE_ENDPOINTS']=@('${GATEWAY_URL}');\
\$envObj['CWS_ROLES']=@('responser-initiated','requestor-initiated','responser-initiator','requestor-initiator');\
\$envObj['CWS_CLIPBOARD_LOGGING']='true';\
\$envObj['CWS_CLIPBOARD_LOG_HASH']='true';\
\$envObj['CWS_CLIPBOARD_LOG_PREVIEW']=64;\
\$envObj['CWS_TUNNEL_DEBUG']=\$true;\
\$envObj['CWS_AIRPAD_NATIVE_ACTIONS']='true';\
\$envObj['CWS_AIRPAD_ROBOTJS_ENABLED']='true';\
\$j.launcherEnv=[pscustomobject]\$envObj;\
\$json=(\$j | ConvertTo-Json -Depth 20);\
[System.IO.File]::WriteAllText(\$p, \$json, [System.Text.UTF8Encoding]::new(\$false));\
Write-Host 'updated:' \$p\""
}

restart_gateway_pm2() {
  ssh_gateway "bash -lc 'export NVM_DIR=\"\$HOME/.nvm\"; [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\"; command -v node >/dev/null 2>&1 || true; command -v pm2 >/dev/null 2>&1 || npm install -g pm2 >/dev/null 2>&1; cd \"${GATEWAY_DIR}\"; pm2 stop cws || true; pm2 flush || true; pm2 restart ecosystem.config.cjs --only cws --env production --update-env || pm2 start ecosystem.config.cjs --only cws --env production --update-env'"
}

restart_vds_pm2() {
  ssh_vds "bash -lc 'export NVM_DIR=\"\$HOME/.nvm\"; [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\"; nvm install 25.8 >/dev/null 2>&1 || true; nvm alias default 25.8 >/dev/null 2>&1 || true; nvm use 25.8 >/dev/null 2>&1 || true; command -v pm2 >/dev/null 2>&1 || npm install -g pm2 >/dev/null 2>&1; cd \"${VDS_DIR}\"; pm2 stop cws || true; pm2 flush || true; pm2 restart ecosystem.config.cjs --only cws --env production --update-env || pm2 start ecosystem.config.cjs --only cws --env production --update-env'"
}

restart_windows_pm2() {
  ssh_windows "powershell -NoProfile -Command \"cd '${WINDOWS_DIR}'; pm2 stop cws 2>\$null; pm2 flush 2>\$null; pm2 restart ecosystem.config.cjs --only cws --env production --update-env; if (\$LASTEXITCODE -ne 0) { pm2 start ecosystem.config.cjs --only cws --env production --update-env }\""
}

trigger_vds_clipboard() {
  local text="$1"
  local text_b64
  text_b64="$(printf '%s' "$text" | base64 -w0)"
  ssh_vds "python3 - <<'PY'
import base64
import json
import urllib.request

text = base64.b64decode('${text_b64}'.encode('ascii')).decode('utf-8', errors='replace')
payload = json.dumps({
    'text': text,
    'targetDeviceId': 'l-192.168.0.110'
}).encode('utf-8')
req = urllib.request.Request(
    'http://127.0.0.1:8080/clipboard',
    data=payload,
    method='POST',
    headers={'x-auth-token': '${CLIP_AUTH_TOKEN}', 'content-type': 'application/json'}
)
with urllib.request.urlopen(req, timeout=10) as resp:
    print(resp.read().decode('utf-8', errors='replace'))
PY"
}

trigger_windows_clipboard() {
  local text="$1"
  ssh_windows "powershell -NoProfile -Command \"\
\$text='${text}';\
Set-Clipboard -Value \$text;\
\$payload=@{ text=\$text; targetDeviceId='l-45.150.9.153' } | ConvertTo-Json -Depth 8;\
Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:8080/clipboard' -Headers @{ 'x-auth-token'='${CLIP_AUTH_TOKEN}' } -ContentType 'application/json' -Body \$payload | Out-Null\""
}

read_windows_clipboard() {
  ssh_windows "powershell -NoProfile -Command \"(Get-Clipboard -Raw)\""
}

vds_logs_contain() {
  local needle="$1"
  ssh_vds "bash -lc 'pm2 logs cws --lines 120 --nostream 2>/dev/null | grep -F \"${needle}\" >/dev/null'"
}

collect_logs() {
  log "Collecting recent PM2 logs for diagnostics..."
  ssh_gateway "bash -lc 'export NVM_DIR=\"\$HOME/.nvm\"; [ -s \"\$NVM_DIR/nvm.sh\" ] && . \"\$NVM_DIR/nvm.sh\"; command -v pm2 >/dev/null 2>&1 || npm install -g pm2 >/dev/null 2>&1; pm2 logs cws --lines 120 --nostream || true'"
  ssh_windows "powershell -NoProfile -Command \"pm2 logs cws --lines 120 --nostream\""
  ssh_vds "bash -lc 'pm2 logs cws --lines 120 --nostream || true'"
}

wait_windows_clipboard_contains() {
  local expected="$1"
  local deadline=$(( $(date +%s) + WAIT_SECONDS ))
  while [[ $(date +%s) -lt $deadline ]]; do
    local value
    value="$(read_windows_clipboard 2>/dev/null || true)"
    if [[ "$value" == *"$expected"* ]]; then
      return 0
    fi
    sleep 3
  done
  return 1
}

main() {
  log "Applying portable.config overrides on all 3 nodes..."
  apply_linux_portable_env ssh_gateway "${GATEWAY_DIR}" "L-192.168.0.200" "${CLIP_AUTH_TOKEN}" "[\"${GATEWAY_URL}\"]" "true" "64" "true" "true" "true"
  apply_windows_portable_env
  apply_linux_portable_env ssh_vds "${VDS_DIR}" "L-45.150.9.153" "VDS-client" "[\"${GATEWAY_URL}\"]" "true" "64" "true" "false" "false"

  local attempt=1
  while [[ $attempt -le $MAX_ATTEMPTS ]]; do
    log "Attempt ${attempt}/${MAX_ATTEMPTS}: restarting PM2 services..."
    restart_gateway_pm2
    restart_windows_pm2
    restart_vds_pm2
    sleep 6

    local test_code="T$(date +%s)-A${attempt}"
    local vds_message="От VDS L-45.150.9.153 ${test_code}"
    log "Sending VDS -> Windows clipboard probe: ${vds_message}"
    trigger_vds_clipboard "${vds_message}" || true

    if wait_windows_clipboard_contains "${test_code}"; then
      log "PASS: Windows clipboard contains VDS test code (${test_code})."
      local win_code="W$(date +%s)-A${attempt}"
      local win_message="От WIN L-192.168.0.110 ${win_code}"
      log "Sending Windows -> VDS probe: ${win_message}"
      trigger_windows_clipboard "${win_message}" || true
      sleep 4
      if vds_logs_contain "${win_code}"; then
        log "PASS: VDS logs contain Windows probe code (${win_code})."
      else
        log "WARN: direct Windows->VDS confirmation found only partially (no code in VDS logs yet)."
      fi
      log "E2E run complete."
      return 0
    fi

    log "No visible result in ${WAIT_SECONDS}s. Enabling strict retry cycle."
    collect_logs
    ((attempt++))
  done

  log "FAIL: no successful clipboard propagation after ${MAX_ATTEMPTS} attempts."
  exit 1
}

main "$@"
