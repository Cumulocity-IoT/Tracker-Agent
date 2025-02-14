#!/bin/bash

# CSV file containing device details
CSV_FILE="devices.csv"
LOG_FILE="device_creation.log"

# Supported operations (comma-separated), default: c8y_Restart,c8y_Command
SUPPORTED_OPERATIONS="${1:-c8y_Restart,c8y_Command}"

# Required availability interval (default to 600 seconds)
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

# Initialize log file
echo "Device Creation Log - $(date)" > "$LOG_FILE"
echo "----------------------------------------" >> "$LOG_FILE"

# Processing CSV
while IFS=',' read -r IMEI TYPE MODEL MANUFACTURER PROTOCOL; do
    echo "Raw Data: $IMEI - $TYPE - $MODEL - $MANUFACTURER - $PROTOCOL"
    echo "Variables: $IMEI - $TYPE - $MODEL - $MANUFACTURER - $PROTOCOL"

    # Validate IMEI format (15 digits)
    create_device "$IMEI" "$TYPE" "$MODEL" "$MANUFACTURER" "$PROTOCOL"

done < "$CSV_FILE"

echo "🚀 Device creation process completed." >> "$LOG_FILE"

# Function to create a device with retry mechanism
create_device() {
    local IMEI="$1"
    local TYPE="$2"
    local MODEL="$3"
    local MANUFACTURER="$4"
    local PROTOCOL="$5"
    local RETRIES=3
    local ATTEMPT=1
    local DEVICE_NAME="Tracker-$IMEI"

    while (( ATTEMPT <= RETRIES )); do
        echo "Attempt $ATTEMPT: Creating device $DEVICE_NAME with IMEI: $IMEI"

        DEVICE_ID=$(c8y inventory create -f --name "$DEVICE_NAME" --type "$TYPE" --data "{
            \"owner\": \"$OWNER\",
            \"c8y_Manufacturer\": \"$MANUFACTURER\",
            \"c8y_IsDevice\": {},
            \"c8y_Mobile\": {\"imei\": \"$IMEI\"},
            \"c8y_Hardware\": {\"model\": \"$MODEL\"},
            \"c8y_CommunicationMode\": {\"mode\": \"$PROTOCOL\"},
            \"com_cumulocity_model_Agent\": {},
            \"c8y_SupportedOperations\": $SUPPORTED_OPS_JSON,
            \"c8y_RequiredAvailability\": {\"responseInterval\": $REQUIRED_INTERVAL}
        }" --output json | jq -r ".id")

        if [[ -n "$DEVICE_ID" ]]; then
            echo "✅ Device created with ID: $DEVICE_ID" >> "$LOG_FILE"
            create_identity "$IMEI" "$DEVICE_ID"
            return
        else
            echo "⚠️ Failed to create device on attempt $ATTEMPT for IMEI: $IMEI" >> "$LOG_FILE"
        fi

        ((ATTEMPT++))
        sleep 2
    done

    echo "❌ Device creation failed after $RETRIES attempts for IMEI: $IMEI" >> "$LOG_FILE"
}

# Function to create identity
create_identity() {
    local IMEI="$1"
    local DEVICE_ID="$2"

    echo "About to create identity with variables: $IMEI - $DEVICE_ID"

    if c8y identity create -f --name "$IMEI" --type "c8y_IMEI" --device "$DEVICE_ID" >/dev/null 2>&1; then
        echo "🔗 Identity created for IMEI: $IMEI" >> "$LOG_FILE"
    else
        echo "❌ Failed to create identity for IMEI: $IMEI" >> "$LOG_FILE"
    fi
}
