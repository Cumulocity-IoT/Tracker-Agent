# 📡 Cumulocity Device Provisioner

A Bash script to bulk-create device inventory Managed Objects in [Cumulocity IoT](https://cumulocity.com/) from a CSV file and automatically attach external identities (IMEI).

---

## ✨ Features

- 📄 Reads device details from a structured CSV file
- 🏗️ Creates fully populated Managed Objects in the Cumulocity inventory
- 🔗 Attaches external identity (`c8y_IMEI`) to each created device
- 🔁 Automatic retry with exponential back-off on failure
- 🧹 Safe temp file handling with automatic cleanup on exit
- 📊 Summary report of processed vs failed devices

---

## 📋 Prerequisites

| Requirement | Notes |
|---|---|
| [`go-c8y-cli`](https://goc8y.io) | Must be installed and configured |
| [`jq`](https://stedolan.github.io/jq/) | JSON processor (`brew install jq` / `apt install jq`) |
| `bash` ≥ 4.0 | Standard on Linux; use Homebrew bash on macOS |
| Active Cumulocity session | Configured via `c8y sessions` |

---

## 📁 CSV Format

The input file must be named `devices.csv` (or overridden via `CSV_FILE` env var) with the following columns:

```csv
imei,deviceType,model,manufacturer,CommunicationMode
353201350644935,c8y_Tracker,FMC 920,Teltonika,TCP
```

| Column | Description | Example |
|---|---|---|
| `imei` | Device IMEI used as external identity | `353201350644935` |
| `deviceType` | Cumulocity managed object type | `c8y_Tracker` |
| `model` | Hardware model name | `FMC 920` |
| `manufacturer` | Device manufacturer | `Teltonika` |
| `CommunicationMode` | Communication protocol | `TCP` |

> ⚠️ The header row is automatically detected and skipped. Blank lines are also ignored.

---

## 🚀 Usage

```bash
# Basic usage (uses defaults)
./provision_devices.sh

# Custom supported operations
./provision_devices.sh "c8y_Restart,c8y_Command,c8y_LogfileRequest"

# Custom supported operations + required availability interval (seconds)
./provision_devices.sh "c8y_Restart,c8y_Command" 30

# Point to a different CSV file
CSV_FILE=my_devices.csv ./provision_devices.sh
```

### Arguments

| Position | Variable | Default | Description |
|---|---|---|---|
| `$1` | `SUPPORTED_OPERATIONS` | `c8y_Restart,c8y_Command` | Comma-separated list of supported operations |
| `$2` | `REQUIRED_INTERVAL` | `10` | Required availability response interval (seconds) |

---

## 🏗️ Managed Object Structure

Each device is created with the following fragment structure:

```json
{
  "name": "Tracker-<IMEI>",
  "type": "<deviceType>",
  "owner": "service_tcp-agent",
  "c8y_IsDevice": {},
  "com_cumulocity_model_Agent": {},
  "c8y_Manufacturer": "<manufacturer>",
  "c8y_Mobile": { "imei": "<IMEI>" },
  "c8y_Hardware": { "model": "<model>" },
  "c8y_CommunicationMode": { "mode": "<CommunicationMode>" },
  "c8y_SupportedOperations": ["c8y_Restart", "c8y_Command"],
  "c8y_RequiredAvailability": { "responseInterval": 10 }
}
```

After creation, an external identity is registered:

```json
{
  "type": "c8y_IMEI",
  "externalId": "<IMEI>"
}
```

---

## ⚙️ Configuration

The following constants can be edited directly in the script or overridden via environment variables:

| Variable | Default | Description |
|---|---|---|
| `CSV_FILE` | `devices.csv` | Path to the input CSV file |
| `OWNER` | `service_tcp-agent` | Owner assigned to each managed object |
| `MAX_RETRIES` | `3` | Max creation attempts per device |

---

## 🔄 Retry & Back-off Behaviour

If a device creation request fails, the script retries up to `MAX_RETRIES` times with an incremental delay:

| Attempt | Wait before next |
|---|---|
| 1 | — |
| 2 | 4 seconds |
| 3 | 6 seconds |

If all attempts are exhausted, the device is marked as **failed** in the final summary.

---

## 📤 Output

During execution, the script emits timestamped log lines:

```
2025-01-15 10:32:01 ⏳ Attempt 1/3 — creating device: Tracker-353201350644935 (IMEI: 353201350644935)
2025-01-15 10:32:02 ✅ Device created — ID: 12345678
2025-01-15 10:32:02 🔄 Creating external identity for IMEI: 353201350644935 → device 12345678
2025-01-15 10:32:02 🔗 Identity created for IMEI: 353201350644935
2025-01-15 10:32:02 🚀 Done — Processed: 1 | Failed: 0
```

---

## 🛡️ Error Handling

- `set -euo pipefail` ensures the script exits on unexpected errors
- Non-JSON API responses are detected and logged without crashing
- Identity creation failures are logged but do not abort remaining devices
- The session login is validated before processing begins
- Temp files are always removed via `trap ... EXIT`

---

## 📂 Project Structure

```
.
├── provision_devices.sh   # Main provisioning script
└── devices.csv            # Input CSV file (you provide this)
```

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-change`
3. Commit your changes: `git commit -m "feat: add my change"`
4. Push and open a Pull Request

---

## 📜 License

MIT License — see [LICENSE](./LICENSE) for details.