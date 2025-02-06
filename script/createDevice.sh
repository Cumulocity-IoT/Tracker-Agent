#!/bin/bash

# CSV file containing device details
CSV_FILE="devices.csv"
LOG_FILE="device_creation.log"

# Supported operations (comma-separated, e.g., "c8y_Restart,c8y_Command")
SUPPORTED_OPERATIONS=${1:-"c8y_Restart,c8y_Command"}

# Required availability interval (default to 600 seconds if not provided)
REQUIRED_INTERVAL=10

# Owner assignment
OWNER="service_tcp-agent"

# Convert supported operations to JSON array
SUPPORTED_OPS_JSON=$(echo "$SUPPORTED_OPERATIONS" | awk -F',' '{ 
    printf "["; 
    for(i=1; i<=NF; i++) { 
        printf "\"" $i "\""; 
        if(i<NF) printf ","; 
    } 
    printf "]" 
}')

# Initialize log file
echo "Device Creation Log - $(date)" > "$LOG_FILE"
echo "----------------------------------------" >> "$LOG_FILE"

# Function to create a device with retry mechanism
create_device() {
    local imei=$1
    local type=$2
    local retries=3
    local attempt=1

    DEVICE_NAME="Tracker-$imei"

    while [ $attempt -le $retries ]; do
        echo "Attempt $attempt: Creating device $DEVICE_NAME with IMEI: $imei"
        DEVICE_ID=$(c8y inventory create \
            --name "$DEVICE_NAME" \
            --type "$type" \
            --owner "$OWNER" \
            --data "{
                \"c8y_IsDevice\": {},
                \"c8y_Mobile\": { \"imei\": \"$imei\" },
                \"com_cumulocity_model_Agent\": {},
                \"c8y_SupportedOperations\": $SUPPORTED_OPS_JSON,
                \"c8y_RequiredAvailability\": { \"responseInterval\": $REQUIRED_INTERVAL }
            }" \
            --output json | jq -r '.id')

        if [ -n "$DEVICE_ID" ]; then
            echo "✅ Device created with ID: $DEVICE_ID" | tee -a "$LOG_FILE"
            create_identity "$imei" "$DEVICE_ID"
            return 0
        else
            echo "⚠️ Failed to create device on attempt $attempt for IMEI: $imei" | tee -a "$LOG_FILE"
        fi

        attempt=$((attempt + 1))
        sleep 2  # Wait before retrying
    done

    echo "❌ Device creation failed after $retries attempts for IMEI: $imei" | tee -a "$LOG_FILE"
    return 1
}

# Function to create identity
create_identity() {
    local imei=$1
    local device_id=$2

    if c8y identity create --name "$imei" --type "c8y_IMEI" --device "$device_id"; then
        echo "🔗 Identity created for IMEI: $imei" | tee -a "$LOG_FILE"
    else
        echo "❌ Failed to create identity for IMEI: $imei" | tee -a "$LOG_FILE"
    fi
}

# Processing CSV
tail -n +2 "$CSV_FILE" | while IFS=',' read -r _ imei type
do
    # Validate IMEI format (basic check for 15 digits)
    if [[ ! $imei =~ ^[0-9]{15}$ ]]; then
        echo "❗ Invalid IMEI format: $imei. Skipping." | tee -a "$LOG_FILE"
        continue
    fi

    create_device "$imei" "$type"
done

echo "🚀 Device creation process completed." | tee -a "$LOG_FILE"
