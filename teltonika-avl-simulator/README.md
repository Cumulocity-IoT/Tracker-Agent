# Teltonika AVL Simulator — CODEC 8 & CODEC 8E

A fully featured **Teltonika AVL TCP simulator** that generates and sends realistic GPS tracking packets using:

- **CODEC 8** (`0x08`)
- **CODEC 8E** (`0x8E` – Extended)

Supports:

- Multi-record AVL packets  
- Realistic GPS movement simulation  
- IMEI handshake  
- Persistent or reconnect-per-packet modes  
- Hex dump inspection mode  
- Correct CRC-16/IBM calculation  
- Custom ACK size (1 or 4 bytes)  

---

## Protocol Reference

Teltonika CODEC documentation:  
https://wiki.teltonika-networks.com/view/CODEC

---

## 🏗 Packet Structure

### Common TCP Wrapper (Both CODEC 8 & 8E)

```
[Preamble 4B = 0x00000000]
[Data Length 4B]
[Codec ID 1B]
[Record Count 1B]
[AVL Records...]
[Record Count 1B]
[CRC-16/IBM 4B]
```

CRC uses:

- Polynomial: `0xA001`
- Initial value: `0x0000`
- Calculated over: `CodecID → second Record Count`

---

## 🔎 CODEC Differences

| Feature | CODEC 8 | CODEC 8E |
|----------|----------|----------|
| Codec ID | `0x08` | `0x8E` |
| IO ID size | 1 byte | 2 bytes |
| IO count size | 1 byte | 2 bytes |
| Extended IDs (>255) | ❌ | ✅ |
| NX (variable length) | ❌ | ✅ (always 0 here) |

---

## 🛰 GPS Simulation

The simulator generates:

- Sequential timestamps (1 second apart)
- Realistic movement along a track
- Heading jitter
- Speed variation
- Voltage drift
- Odometer increment
- GSM signal fluctuation

Default start location:

Vilnius, Lithuania (Teltonika HQ region)

---

## Installation

No external dependencies required.

```bash
git clone https://github.com/Cumulocity-IoT/Tracker-Agent
cd teltonika-avl-simulator
python teltonika_simulator.py --help
```

Requires:

- Python 3.8+

---

## Usage

### Print Mode (No TCP)

Generate packet and print full hex dump:

```bash
python teltonika_simulator.py --codec 8e --mode print --count 5
```

Save raw packet to file:

```bash
python teltonika_simulator.py --codec 8 --mode print --output packet.bin
```

---

### Send Mode (TCP Client)

Send packet to server:

```bash
python teltonika_simulator.py --codec 8e --mode send --host 127.0.0.1 --port 8888
```

Reconnect for every packet (for servers that close connection):

```bash
python teltonika_simulator.py --codec 8 --mode send --host 127.0.0.1 --port 8888 --reconnect
```

Unlimited continuous sending every 3 seconds:

```bash
python teltonika_simulator.py \
  --codec 8e \
  --mode send \
  --host 127.0.0.1 \
  --port 8888 \
  --packets 0 \
  --interval 3000
```

---

## CLI Parameters

### Codec & Mode

| Argument | Description |
|----------|------------|
| `--codec {8,8e}` | Select CODEC 8 or CODEC 8E |
| `--mode {print,send}` | Print hex or send over TCP |

---

### Server Settings

| Argument | Default | Description |
|----------|----------|-------------|
| `--host` | 127.0.0.1 | TCP server IP |
| `--port` | 8888 | TCP port |
| `--timeout` | 10 | Socket timeout (seconds) |
| `--ack-size {1,4}` | 4 | Expected ACK size |

---

### Connection Behavior

| Argument | Description |
|----------|------------|
| `--reconnect` | Reconnect + IMEI before each packet |

---

### Device Identity

| Argument | Default |
|----------|----------|
| `--imei` | 353201350644990 |

---

### GPS Parameters

| Argument | Default |
|----------|----------|
| `--lat` | 54.687157 |
| `--lng` | 25.279652 |
| `--alt` | 150 |
| `--angle` | 45 |
| `--speed` | 60 |
| `--satellites` | 9 |
| `--priority {0,1,2}` | 0 |

---

### Packet Parameters

| Argument | Default | Description |
|----------|----------|-------------|
| `--count` | 5 | Records per packet |
| `--packets` | 0 | Number of packets (0 = unlimited) |
| `--interval` | 5000 | Interval between packets (ms) |

---

## IMEI Handshake

Before AVL data transmission:

```
[Length 2B][IMEI ASCII]
```

Server must respond:

```
0x01 → accepted
```

---

## ACK Handling

Supports:

- 4-byte standard Teltonika ACK (big-endian integer)
- 1-byte custom ACK (some servers)

Configure using:

```bash
--ack-size 1
```

---

## CRC Validation

The simulator calculates CRC-16/IBM exactly as Teltonika devices do.

Common failure causes if server closes connection:

- ❌ CRC mismatch  
- ❌ Wrong IO structure  
- ❌ Wrong ACK size  
- ❌ Server closes per packet (use `--reconnect`)  

---

## Typical Use Cases

- Testing Teltonika server implementations
- IoT platform integration testing
- Custom TCP ingestion testing
- Debugging AVL packet structure
- Learning Teltonika binary protocol
- CI automation for protocol validation

---

## Design Highlights

- Clean modular structure
- Robust TCP read handling
- Graceful reconnect logic
- Movement simulation engine
- Accurate protocol compliance
- Highly configurable CLI

---

## 📄 License

MIT License (or your preferred license)

---

## 👨‍💻 Author

Your Name  
IoT / AVL / Telematics Developer  