#!/bin/bash
set -euo pipefail

# ─── Configuration ────────────────────────────────────────────────────────────
CSV_FILE="${CSV_FILE:-devices.csv}"
SUPPORTED_OPERATIONS="${1:-c8y_Restart,c8y_Command}"
REQUIRED_INTERVAL="${2:-10}"
OWNER="service_tcp-agent"
MAX_RETRIES=3

# ─── Known CSV columns mapped into specific Cumulocity fragments.
# Any column NOT in this list is added as a dynamic top-level fragment.
KNOWN_COLUMNS="imei deviceType model manufacturer CommunicationMode serialNumber iccid imsi firmwareVersion hardwareRevision"

# ─── Disable all interactive confirmations (non-interactive/CI mode) ─────────
export C8Y_SETTINGS_CI=true

# ─── Build supported-ops JSON array safely via jq ─────────────────────────────
SUPPORTED_OPS_JSON=$(printf '%s' "$SUPPORTED_OPERATIONS" \
    | jq -Rc 'split(",")')

# ─── Logging helper ───────────────────────────────────────────────────────────
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

# ─── Activate session ─────────────────────────────────────────────────────────
if ! eval "$(c8y sessions login --shell bash)"; then
    log "🚨 Failed to activate c8y session. Aborting."
    exit 1
fi

# ─── Read CSV headers into global array ───────────────────────────────────────
read_headers() {
    local header_line
    header_line=$(head -n 1 "$CSV_FILE" | tr -d '\r\n')
    IFS=',' read -ra HEADERS <<< "$header_line"
    log "📋 Headers found (${#HEADERS[@]}): ${HEADERS[*]}"
}

# ─── create_identity ──────────────────────────────────────────────────────────
create_identity() {
    local IMEI="$1"
    local DEVICE_ID="$2"

    log "🔄 Creating external identity — type: c8y_IMEI, externalId: $IMEI, device: $DEVICE_ID"

    local RESPONSE EXIT_CODE
    # Correct c8y api syntax: METHOD and URL are positional args, data via --data
    RESPONSE=$(c8y api POST "/identity/globalIds/${DEVICE_ID}/externalIds" \
        --data "externalId=${IMEI},type=c8y_IMEI" \
        --output json \
        --view off \
        --noLog \
        --nullInput \
        --force \
        2>&1) && EXIT_CODE=0 || EXIT_CODE=$?

    log "🧪 Identity exit code: $EXIT_CODE | Response: $RESPONSE"

    if echo "$RESPONSE" | jq -e '.externalId' >/dev/null 2>&1; then
        log "🔗 Identity confirmed — externalId: $(echo "$RESPONSE" | jq -r '.externalId')"
    else
        log "❌ Identity creation failed for IMEI: $IMEI — $RESPONSE"
    fi
}

# ─── create_device ────────────────────────────────────────────────────────────
create_device() {
    declare -A ROW
    while [[ $# -gt 0 ]]; do
        ROW["$1"]="$2"
        shift 2
    done

    local IMEI="${ROW[imei]:-}"
    local TYPE="${ROW[deviceType]:-}"
    local MODEL="${ROW[model]:-}"
    local MANUFACTURER="${ROW[manufacturer]:-}"
    local PROTOCOL="${ROW[CommunicationMode]:-}"
    local SERIAL="${ROW[serialNumber]:-}"
    local ICCID="${ROW[iccid]:-}"
    local IMSI="${ROW[imsi]:-}"
    local FIRMWARE="${ROW[firmwareVersion]:-}"
    local HW_REV="${ROW[hardwareRevision]:-}"
    local DEVICE_NAME="Tracker-$IMEI"
    local ATTEMPT=1

    local PAYLOAD
    PAYLOAD=$(jq -cn \
        --arg name         "$DEVICE_NAME" \
        --arg type         "$TYPE" \
        --arg owner        "$OWNER" \
        --arg manufacturer "$MANUFACTURER" \
        --arg imei         "$IMEI" \
        --arg iccid        "$ICCID" \
        --arg imsi         "$IMSI" \
        --arg model        "$MODEL" \
        --arg serial       "$SERIAL" \
        --arg hwrev        "$HW_REV" \
        --arg firmware     "$FIRMWARE" \
        --arg mode         "$PROTOCOL" \
        --argjson ops      "$SUPPORTED_OPS_JSON" \
        --argjson interval "$REQUIRED_INTERVAL" \
        '{
            name:                       $name,
            type:                       $type,
            owner:                      $owner,
            c8y_IsDevice:               {},
            com_cumulocity_model_Agent: {},
            c8y_Manufacturer:           $manufacturer,
            c8y_Mobile: {
                imei:  $imei,
                iccid: $iccid,
                imsi:  $imsi
            },
            c8y_Hardware: {
                model:        $model,
                serialNumber: $serial,
                revision:     $hwrev
            },
            c8y_Firmware: {
                version: $firmware
            },
            c8y_CommunicationMode:    { mode: $mode },
            c8y_SupportedOperations:  $ops,
            c8y_RequiredAvailability: { responseInterval: $interval }
        }')

    # Dynamically merge unknown columns as top-level fragments
    for COL in "${!ROW[@]}"; do
        if ! grep -qw "$COL" <<< "$KNOWN_COLUMNS"; then
            local VAL="${ROW[$COL]}"
            PAYLOAD=$(printf '%s' "$PAYLOAD" \
                | jq -c --arg k "$COL" --arg v "$VAL" '. + {($k): $v}')
            log "➕ Dynamic fragment: $COL = $VAL"
        fi
    done

    log "📦 Payload: $PAYLOAD"

    while (( ATTEMPT <= MAX_RETRIES )); do
        log "⏳ Attempt $ATTEMPT/$MAX_RETRIES — creating device: $DEVICE_NAME (IMEI: $IMEI)"

        local RESPONSE DEVICE_ID
        RESPONSE=$(printf '%s' "$PAYLOAD" | c8y inventory create \
            --force \
            --output json \
            --view off \
            --noLog 2>&1) || true

        if echo "$RESPONSE" | jq -e . >/dev/null 2>&1; then
            DEVICE_ID=$(echo "$RESPONSE" | jq -r '.id // empty')
        else
            DEVICE_ID=""
            log "⚠️  Non-JSON response: $RESPONSE"
        fi

        if [[ -n "$DEVICE_ID" ]]; then
            log "✅ Device created — ID: $DEVICE_ID"
            create_identity "$IMEI" "$DEVICE_ID"
            return 0
        fi

        log "⚠️  Attempt $ATTEMPT failed for IMEI: $IMEI"
        (( ATTEMPT++ ))
        (( ATTEMPT <= MAX_RETRIES )) && sleep $(( ATTEMPT * 2 ))
    done

    log "❌ Device creation failed after $MAX_RETRIES attempts for IMEI: $IMEI"
    return 1
}

# ─── Validate CSV ─────────────────────────────────────────────────────────────
if [[ ! -f "$CSV_FILE" ]]; then
    log "🚨 CSV file '$CSV_FILE' not found!"
    exit 1
fi

read_headers

# ─── Process CSV rows ─────────────────────────────────────────────────────────
PROCESSED=0
FAILED=0
ROW_NUM=0
SKIP_HEADER=true

while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$'\r'/}"
    [[ -z "$line" ]] && continue

    if [[ "$SKIP_HEADER" == "true" ]]; then
        SKIP_HEADER=false
        continue
    fi

    ROW_NUM=$(( ROW_NUM + 1 ))
    log "📄 Row $ROW_NUM: $line"

    IFS=',' read -ra VALUES <<< "$line"

    KV_ARGS=()
    for i in "${!HEADERS[@]}"; do
        COL="${HEADERS[$i]}"
        VAL="${VALUES[$i]:-}"
        KV_ARGS+=("$COL" "$VAL")
    done

    log "🔑 Parsed ${#KV_ARGS[@]} fields for IMEI: ${VALUES[0]}"

    if create_device "${KV_ARGS[@]}"; then
        (( PROCESSED++ ))
    else
        (( FAILED++ ))
    fi

done < "$CSV_FILE"

log "🚀 Done — Processed: $PROCESSED | Failed: $FAILED"