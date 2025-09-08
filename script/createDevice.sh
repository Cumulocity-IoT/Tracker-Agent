#!/bin/bash

# CSV file containing device details
CSV_FILE="devices.csv"

# Activate session
eval "$(c8y sessions login --shell bash)"

# Supported operations (default: c8y_Restart, c8y_Command)
SUPPORTED_OPERATIONS="${1:-c8y_Restart,c8y_Command}"

# Required availability interval (default to 10 seconds)
REQUIRED_INTERVAL="${2:-10}"

# Owner assignment
OWNER="service_tcp-agent"

# Convert supported operations to JSON array
IFS=',' read -ra OPS <<< "$SUPPORTED_OPERATIONS"
SUPPORTED_OPS_JSON="[\"${OPS[0]}\""
for ((i = 1; i < ${#OPS[@]}; i++)); do
    SUPPORTED_OPS_JSON+=",\"${OPS[i]}\""
done
SUPPORTED_OPS_JSON+="]"

# Function to create a device with retry mechanism
create_device() {
    local IMEI="$1"
    local TYPE="$2"
    local MODEL="$3"
    local MANUFACTURER="$4"
    local PROTOCOL="$5"
    local RETRIES=1
    local ATTEMPT=1
    local DEVICE_NAME="Tracker-$IMEI"

    while (( ATTEMPT <= RETRIES )); do
        echo "$(date '+%Y-%m-%d %H:%M:%S') - Attempt $ATTEMPT: Creating device $DEVICE_NAME with IMEI: $IMEI"
        local payload=$(jq -c --null-input \
            --arg imei "$IMEI" \
            --arg model "$MODEL" \
            --arg manufacturer "$MANUFACTURER" \
            --arg mode "$PROTOCOL" \
            --argjson ops "$SUPPORTED_OPS_JSON" \
            --arg interval "$REQUIRED_INTERVAL" '
            {
                owner: "service_tcp-agent",
                c8y_Manufacturer: $manufacturer,
                c8y_IsDevice: {},
                c8y_Mobile: {imei: $imei},
                c8y_Hardware: {model: $model},
                c8y_CommunicationMode: {mode: $mode},
                com_cumulocity_model_Agent: {},
                c8y_SupportedOperations: $ops,
                c8y_RequiredAvailability: {responseInterval: ($interval | tonumber)}
            }')
        echo "$payload" > device.json
        echo "$payload"

        RESPONSE=$(c8y inventory create -f --name "$DEVICE_NAME" --type "$TYPE" --data @device.json --output json --debug)
        echo "🧪 RAW RESPONSE: $RESPONSE"
        DEVICE_ID=$(echo "$RESPONSE" | jq -r ".id")

        if [[ -n "$DEVICE_ID" && "$DEVICE_ID" != "null" ]]; then
            echo "✅ $(date '+%Y-%m-%d %H:%M:%S') - Device created with ID: $DEVICE_ID"
            create_identity "$IMEI" "$DEVICE_ID"
            return
        else
            echo "⚠️ $(date '+%Y-%m-%d %H:%M:%S') - Failed to create device on attempt $ATTEMPT for IMEI: $IMEI"
        fi

        ((ATTEMPT++))
        sleep 2
    done

    echo "❌ $(date '+%Y-%m-%d %H:%M:%S') - Device creation failed after $RETRIES attempts for IMEI: $IMEI"
}

# Function to create identity
create_identity() {
    local IMEI="$1"
    local DEVICE_ID="$2"

    echo "🔄 $(date '+%Y-%m-%d %H:%M:%S') - Creating identity for IMEI: $IMEI"

    if c8y identity create -f --name "$IMEI" --type "c8y_IMEI" --device "$DEVICE_ID" >/dev/null 2>&1; then
        echo "🔗 $(date '+%Y-%m-%d %H:%M:%S') - Identity created for IMEI: $IMEI"
    else
        echo "❌ $(date '+%Y-%m-%d %H:%M:%S') - Failed to create identity for IMEI: $IMEI"
    fi
}

# Validate CSV file existence
if [[ ! -f "$CSV_FILE" ]]; then
    echo "🚨 Error: CSV file '$CSV_FILE' not found!"
    exit 1
fi

# Processing CSV
while IFS=',' read -r IMEI TYPE MODEL MANUFACTURER PROTOCOL; do
    # Skip empty lines
    [[ -z "$IMEI" || -z "$TYPE" || -z "$MODEL" || -z "$MANUFACTURER" || -z "$PROTOCOL" ]] && continue
    create_device "$IMEI" "$TYPE" "$MODEL" "$MANUFACTURER" "$PROTOCOL"
done < <(tail -n +2 "$CSV_FILE")

echo "🚀 $(date '+%Y-%m-%d %H:%M:%S') - Device creation process completed."
