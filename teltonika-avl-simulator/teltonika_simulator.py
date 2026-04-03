#!/usr/bin/env python3
"""
Teltonika AVL Simulator — CODEC 8 & CODEC 8E
=============================================
Generates and sends AVL data packets over TCP to a Teltonika-compatible server.

Protocol references:
  CODEC 8  : https://wiki.teltonika-networks.com/view/CODEC#CODEC_8
  CODEC 8E : https://wiki.teltonika-networks.com/view/CODEC#CODEC_8_Extended

╔══════════════════════════════════════════════════════════════════════════╗
║  CODEC 8  — IO IDs are 1 byte,  IO counts are 1 byte  (CodecID = 0x08) ║
║  CODEC 8E — IO IDs are 2 bytes, IO counts are 2 bytes (CodecID = 0x8E) ║
╚══════════════════════════════════════════════════════════════════════════╝

Common packet wrapper (both codecs):
  [Preamble 4B = 0x00000000]
  [Data Length 4B]
  [Codec ID    1B]
  [Rec Count   1B]
  [AVL Records ...]
  [Rec Count   1B]
  [CRC-16/IBM  4B]   ← upper 2 bytes are always 0x0000

AVL Record (both codecs share the GPS header):
  [Timestamp  8B]  — ms since Unix epoch
  [Priority   1B]  — 0=low, 1=high, 2=panic
  [Longitude  4B signed] — degrees × 10^7
  [Latitude   4B signed] — degrees × 10^7
  [Altitude   2B]
  [Angle      2B]  — 0-360°
  [Satellites 1B]
  [Speed      2B]  — km/h
  [IO Element]

IO Element — CODEC 8:
  [Event IO ID 1B][Total Count 1B]
  [N1 1B][ID 1B, Val 1B] * N1
  [N2 1B][ID 1B, Val 2B] * N2
  [N4 1B][ID 1B, Val 4B] * N4
  [N8 1B][ID 1B, Val 8B] * N8

IO Element — CODEC 8E:
  [Event IO ID 2B][Total Count 2B]
  [N1 2B][ID 2B, Val 1B] * N1
  [N2 2B][ID 2B, Val 2B] * N2
  [N4 2B][ID 2B, Val 4B] * N4
  [N8 2B][ID 2B, Val 8B] * N8
  [NX 2B = 0]   ← variable-length count, always zero here

Usage examples:
  # Print 5-record CODEC 8E packet (hex dump, no TCP)
  python teltonika_simulator.py --codec 8e --mode print --count 5

  # Print 5-record CODEC 8 packet
  python teltonika_simulator.py --codec 8 --mode print --count 5

  # Send CODEC 8E to server (persistent connection)
  python teltonika_simulator.py --codec 8e --mode send --host 127.0.0.1 --port 8888 --count 5

  # Send CODEC 8 with reconnect per packet
  python teltonika_simulator.py --codec 8 --mode send --host 127.0.0.1 --port 8888 --reconnect

  # Unlimited auto-send, CODEC 8E, 3-second interval
  python teltonika_simulator.py --codec 8e --mode send --host 127.0.0.1 --port 8888 \\
      --count 5 --packets 0 --interval 3000
"""

import socket
import struct
import time
import random
import argparse
import logging
import sys
import math
from dataclasses import dataclass, field
from typing import Optional

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("teltonika")

# ─────────────────────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────────────────────

CODEC_8    = "8"
CODEC_8E   = "8e"
CODEC_ID   = {CODEC_8: 0x08, CODEC_8E: 0x8E}

# Default number of AVL records per packet (per task requirement)
DEFAULT_RECORDS = 5

# Simulated route: 5 waypoints near Vilnius, Lithuania (Teltonika HQ city)
ROUTE_WAYPOINTS = [
    (54.687157, 25.279652),
    (54.689500, 25.283100),
    (54.692000, 25.286400),
    (54.694300, 25.290100),
    (54.696800, 25.293500),
    (54.699100, 25.297200),
    (54.701500, 25.300800),
    (54.698000, 25.304200),
    (54.694500, 25.301000),
    (54.691000, 25.297800),
]

# ─────────────────────────────────────────────────────────────────────────────
# CRC-16/IBM  (polynomial 0xA001, initial value 0x0000)
# Applied over the data field only (CodecID…RecCount-end)
# ─────────────────────────────────────────────────────────────────────────────

def crc16_ibm(data: bytes) -> int:
    crc = 0x0000
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc & 0xFFFF


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def _size_of(val: int) -> int:
    """Minimum unsigned byte width (1/2/4/8) needed for val."""
    if val < 0:
        val = val & 0xFFFFFFFFFFFFFFFF
    if val <= 0xFF:         return 1
    if val <= 0xFFFF:       return 2
    if val <= 0xFFFFFFFF:   return 4
    return 8


def _pack_id(codec: str, io_id: int) -> bytes:
    """Pack IO ID as 1 byte (CODEC 8) or 2 bytes (CODEC 8E)."""
    return struct.pack(">B", io_id) if codec == CODEC_8 else struct.pack(">H", io_id)


def _pack_count(codec: str, n: int) -> bytes:
    """Pack an IO count as 1 byte (CODEC 8) or 2 bytes (CODEC 8E)."""
    return struct.pack(">B", n) if codec == CODEC_8 else struct.pack(">H", n)


# ─────────────────────────────────────────────────────────────────────────────
# IO element builder — handles CODEC 8 and CODEC 8E
# ─────────────────────────────────────────────────────────────────────────────

def build_io_element(codec: str, event_io_id: int, io_elements: dict) -> bytes:
    """
    Build the IO element block for a single AVL record.

    CODEC 8  : event_io_id and counts are 1 byte; IO IDs are 1 byte.
               NOTE: IO IDs for CODEC 8 must fit in 1 byte (0-255).
    CODEC 8E : event_io_id and counts are 2 bytes; IO IDs are 2 bytes.
               Also appends NX (variable-length) count = 0.
    """
    # For CODEC 8, cap IO IDs at 255 and skip any that won't fit
    if codec == CODEC_8:
        io_elements = {
            k: v for k, v in io_elements.items() if k <= 0xFF
        }

    buckets: dict[int, list] = {1: [], 2: [], 4: [], 8: []}
    for io_id, val in io_elements.items():
        if val < 0:
            val = val & 0xFFFFFFFFFFFFFFFF
        buckets[_size_of(val)].append((io_id, val))

    fmt_map = {1: "B", 2: "H", 4: "I", 8: "Q"}

    buf = bytearray()
    buf += _pack_id(codec, event_io_id)                        # Event IO ID
    total = sum(len(v) for v in buckets.values())
    buf += _pack_count(codec, total)                           # Total IO count

    for size in (1, 2, 4, 8):
        items = buckets[size]
        buf += _pack_count(codec, len(items))                  # Nx count
        for io_id, val in items:
            buf += _pack_id(codec, io_id)                      # IO ID
            buf += struct.pack(f">{fmt_map[size]}", val)       # Value

    if codec == CODEC_8E:
        buf += struct.pack(">H", 0)                            # NX (variable) = 0

    return bytes(buf)


# ─────────────────────────────────────────────────────────────────────────────
# AVL record builder
# ─────────────────────────────────────────────────────────────────────────────

def build_avl_record(
    codec: str,
    timestamp_ms: int,
    latitude: float,
    longitude: float,
    altitude: int        = 150,
    angle: int           = 0,
    satellites: int      = 9,
    speed: int           = 60,
    priority: int        = 0,
    io_elements: dict    = None,
    event_io_id: int     = 0,
) -> bytes:
    """Build one AVL data record (GPS header + IO element)."""
    lat_int = int(round(latitude  * 10_000_000))
    lng_int = int(round(longitude * 10_000_000))

    buf = bytearray()
    buf += struct.pack(">Q", timestamp_ms & 0xFFFFFFFFFFFFFFFF)  # Timestamp  8B
    buf += struct.pack(">B", priority & 0xFF)                    # Priority   1B
    buf += struct.pack(">i", lng_int)                            # Longitude  4B signed
    buf += struct.pack(">i", lat_int)                            # Latitude   4B signed
    buf += struct.pack(">H", altitude & 0xFFFF)                  # Altitude   2B
    buf += struct.pack(">H", angle & 0x01FF)                     # Angle      2B (0-360)
    buf += struct.pack(">B", satellites & 0xFF)                  # Satellites 1B
    buf += struct.pack(">H", speed & 0xFFFF)                     # Speed      2B
    buf += build_io_element(codec, event_io_id, io_elements or {})
    return bytes(buf)


# ─────────────────────────────────────────────────────────────────────────────
# Packet builder
# ─────────────────────────────────────────────────────────────────────────────

def build_packet(codec: str, records: list) -> bytes:
    """
    Wrap AVL records in a complete CODEC 8 / 8E TCP packet.

    Structure:
      00 00 00 00              Preamble       (4 B)
      XX XX XX XX              Data length    (4 B)
      08 | 8E                  Codec ID       (1 B)
      NN                       Record count   (1 B)
      [record bytes ...]
      NN                       Record count   (1 B)  repeated
      00 00 XX XX              CRC-16/IBM     (4 B)
    """
    cid       = CODEC_ID[codec]
    rec_count = len(records)

    data_field = bytearray()
    data_field += struct.pack(">B", cid)
    data_field += struct.pack(">B", rec_count)
    for rec in records:
        data_field += rec
    data_field += struct.pack(">B", rec_count)

    crc = crc16_ibm(bytes(data_field))

    packet = bytearray()
    packet += b"\x00\x00\x00\x00"
    packet += struct.pack(">I", len(data_field))
    packet += data_field
    packet += struct.pack(">I", crc)            # upper 2 bytes = 0x0000 by design
    return bytes(packet)


# ─────────────────────────────────────────────────────────────────────────────
# IMEI handshake
# ─────────────────────────────────────────────────────────────────────────────

def build_imei_packet(imei: str) -> bytes:
    """[length 2B big-endian][IMEI ASCII bytes]"""
    b = imei.encode("ascii")
    return struct.pack(">H", len(b)) + b


# ─────────────────────────────────────────────────────────────────────────────
# IO element presets
# ─────────────────────────────────────────────────────────────────────────────

def make_io_codec8(speed: int, ignition: bool = True, record_index: int = 0) -> dict:
    """
    CODEC 8 IO elements (IDs must be 1 byte, 0-255).
    Simulates realistic FMB device telemetry with slight variation per record.
    """
    return {
        239: 1 if ignition else 0,                    # Ignition
        240: 1 if speed > 0 else 0,                   # Movement
        69:  1,                                        # GNSS Status (1=fix)
        21:  min(5, max(0, 4 + random.randint(-1,1))), # GSM Signal 0-5
        66:  12500 + record_index * 10,                # Ext voltage mV (slight drain sim)
        24:  speed,                                    # Speed IO
        80:  record_index,                             # Data Mode
        181: 3,                                        # PDOP × 10
        182: 5,                                        # HDOP × 10
        16:  1000 + record_index * 50,                 # Total odometer (m)
    }


def make_io_codec8e(speed: int, ignition: bool = True, record_index: int = 0) -> dict:
    """
    CODEC 8E IO elements (IDs can be 2 bytes, 0-65535).
    Includes extended IDs only available in CODEC 8E.
    """
    return {
        # 1-byte values
        239: 1 if ignition else 0,                     # Ignition
        240: 1 if speed > 0 else 0,                    # Movement
        69:  1,                                         # GNSS Status
        21:  min(5, max(0, 4 + random.randint(-1,1))),  # GSM Signal 0-5
        80:  record_index,                              # Data Mode
        # 2-byte values
        66:  12500 + record_index * 10,                 # Ext voltage mV
        24:  speed,                                     # Speed IO
        181: 3,                                         # PDOP × 10
        182: 5,                                         # HDOP × 10
        # 4-byte values
        16:  1000 + record_index * 50,                  # Total odometer m
        199: record_index * 100,                        # Trip odometer m
        # Extended CODEC 8E IDs (>255)
        389: 1 if ignition else 0,                     # Ignition (extended ID)
        390: speed * 100,                              # Speed × 100 (extended)
        # 8-byte value example
        # (none in this preset — add if your server expects them)
    }


# ─────────────────────────────────────────────────────────────────────────────
# Route / position generator — 5 realistic positions along a track
# ─────────────────────────────────────────────────────────────────────────────

def generate_positions(
    base_lat: float,
    base_lng: float,
    n: int,
    speed_kmh: int,
    base_angle: int,
) -> list:
    """
    Generate n sequential GPS positions moving in the direction of base_angle
    at speed_kmh.  Each position is 1 second apart (speed in m/s → lat/lng delta).
    Returns list of (lat, lng, angle, speed) tuples.
    """
    positions = []
    lat = base_lat
    lng = base_lng

    # Convert speed to degrees per second (approximate)
    mps        = speed_kmh / 3.6
    deg_per_m  = 1.0 / 111_320          # ~1 degree latitude ≈ 111,320 m
    lng_scale  = math.cos(math.radians(lat))  # longitude degree shrinks near poles

    for i in range(n):
        angle_rad = math.radians(base_angle + random.uniform(-3, 3))
        spd       = max(0, speed_kmh + random.randint(-3, 3))
        m_per_s   = spd / 3.6

        dlat = math.cos(angle_rad) * m_per_s * deg_per_m
        dlng = math.sin(angle_rad) * m_per_s * deg_per_m / lng_scale

        lat += dlat
        lng += dlng

        positions.append((
            round(lat, 7),
            round(lng, 7),
            int((base_angle + random.randint(-5, 5)) % 360),
            spd,
        ))

    return positions


# ─────────────────────────────────────────────────────────────────────────────
# Build a list of AVL records
# ─────────────────────────────────────────────────────────────────────────────

def build_avl_records(codec: str, args, base_lat: float, base_lng: float) -> list:
    """
    Build args.count AVL records, each 1 second apart, moving along a track.
    Timestamps run backwards from now so the last record = latest.
    """
    n         = args.count
    now_ms    = int(time.time() * 1000)
    positions = generate_positions(base_lat, base_lng, n, args.speed, args.angle)

    io_fn = make_io_codec8e if codec == CODEC_8E else make_io_codec8

    records = []
    for i, (lat, lng, angle, spd) in enumerate(positions):
        ts = now_ms - (n - 1 - i) * 1000   # oldest first, newest last
        io = io_fn(spd, ignition=True, record_index=i)

        rec = build_avl_record(
            codec        = codec,
            timestamp_ms = ts,
            latitude     = lat,
            longitude    = lng,
            altitude     = args.alt + random.randint(-2, 2),
            angle        = angle,
            satellites   = args.satellites + random.randint(-1, 1),
            speed        = spd,
            priority     = args.priority,
            io_elements  = io,
            event_io_id  = 0,
        )
        records.append(rec)
        log.debug(
            f"  Record {i+1}/{n}: lat={lat:.6f} lng={lng:.6f} "
            f"spd={spd} km/h angle={angle}° ts={ts}"
        )

    return records


# ─────────────────────────────────────────────────────────────────────────────
# Packet pretty-printer
# ─────────────────────────────────────────────────────────────────────────────

def dump_packet_info(codec: str, pkt: bytes, rec_count: int) -> None:
    h        = pkt.hex().upper()
    data_len = struct.unpack(">I", pkt[4:8])[0]
    crc_val  = struct.unpack(">I", pkt[-4:])[0]
    cid_name = f"CODEC {codec.upper()}"
    cid_hex  = f"0x{CODEC_ID[codec]:02X}"

    log.debug(f"┌─ {cid_name} packet breakdown {'─'*(44 - len(cid_name))}")
    log.debug(f"│  Preamble    : {h[0:8]}              (0x00000000)")
    log.debug(f"│  Data length : {h[8:16]}              ({data_len} bytes)")
    log.debug(f"│  Codec ID    : {h[16:18]}                    ({cid_hex})")
    log.debug(f"│  Rec count ↑ : {h[18:20]}                    ({rec_count})")
    avl_hex = h[20:-10]
    log.debug(f"│  AVL data    : {avl_hex[:64]}{'...' if len(avl_hex)>64 else ''}")
    log.debug(f"│  Rec count ↓ : {h[-10:-8]}                    ({rec_count})")
    log.debug(f"│  CRC-16/IBM  : {h[-8:]}              (0x{crc_val:04X})")
    log.debug(f"│  Total size  : {len(pkt)} bytes")
    log.debug("└" + "─" * 55)


def print_packet_dump(codec: str, pkt: bytes, rec_count: int, imei: str) -> None:
    h       = pkt.hex().upper()
    crc_val = struct.unpack(">I", pkt[-4:])[0]
    imei_pkt = build_imei_packet(imei)
    sep = "=" * 74

    print(f"\n{sep}")
    print(f"  TELTONIKA CODEC {codec.upper()} — PACKET DUMP")
    print(sep)
    print(f"  IMEI         : {imei}")
    print(f"  AVL Records  : {rec_count}")
    print(f"  Packet bytes : {len(pkt)}")
    print(f"  CRC-16/IBM   : 0x{crc_val:04X}  (4-byte field: {h[-8:]})")
    print(f"  Codec ID     : 0x{CODEC_ID[codec]:02X}")
    print(sep)

    print("\nIMEI handshake packet:")
    print(f"  {imei_pkt.hex().upper()}")

    print(f"\nAVL packet hex (no spaces):")
    print(f"  {h}")

    print(f"\nAVL packet hex (spaced bytes):")
    spaced = " ".join(h[i:i+2] for i in range(0, len(h), 2))
    # wrap at 72 chars for readability
    words = spaced.split()
    line, lines = [], []
    for w in words:
        line.append(w)
        if len(" ".join(line)) > 70:
            lines.append("  " + " ".join(line[:-1]))
            line = [line[-1]]
    if line:
        lines.append("  " + " ".join(line))
    print("\n".join(lines))

    print(f"\nField breakdown:")
    print(f"  [Preamble  ] {h[0:8]}")
    print(f"  [DataLength] {h[8:16]}")
    print(f"  [CodecID   ] {h[16:18]}  (0x{CODEC_ID[codec]:02X})")
    print(f"  [RecCnt  ↑ ] {h[18:20]}  ({rec_count})")
    print(f"  [AVL Data  ] {h[20:-10]}")
    print(f"  [RecCnt  ↓ ] {h[-10:-8]}  ({rec_count})")
    print(f"  [CRC-16    ] {h[-8:]}")
    print(sep)


# ─────────────────────────────────────────────────────────────────────────────
# Robust TCP receive
# ─────────────────────────────────────────────────────────────────────────────

def recv_exact(sock: socket.socket, n: int) -> bytes:
    """Read exactly n bytes. Returns b'' on clean EOF before first byte."""
    buf = b""
    while len(buf) < n:
        try:
            chunk = sock.recv(n - len(buf))
        except socket.timeout:
            raise socket.timeout(f"Timeout waiting for byte {len(buf)+1}/{n}")
        if not chunk:
            if not buf:
                return b""
            raise ConnectionError(
                f"Connection closed mid-read ({len(buf)}/{n} bytes received)"
            )
        buf += chunk
    return buf


# ─────────────────────────────────────────────────────────────────────────────
# TCP client
# ─────────────────────────────────────────────────────────────────────────────

class TeltonikaClient:
    def __init__(
        self,
        host:      str,
        port:      int,
        imei:      str,
        codec:     str,
        timeout:   float = 10.0,
        reconnect: bool  = False,
        ack_size:  int   = 4,
    ):
        self.host      = host
        self.port      = port
        self.imei      = imei
        self.codec     = codec
        self.timeout   = timeout
        self.reconnect = reconnect   # re-handshake each packet
        self.ack_size  = ack_size    # 4=standard Teltonika, 1=some custom servers
        self.sock      = None

    # ── connection ────────────────────────────────────────────────────────────

    def connect(self) -> None:
        log.info(f"Connecting to {self.host}:{self.port} ...")
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect((self.host, self.port))
        log.info("Connected.")

    def close(self) -> None:
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
            log.info("Connection closed.")

    # ── IMEI handshake ────────────────────────────────────────────────────────

    def send_imei(self) -> bool:
        pkt = build_imei_packet(self.imei)
        log.debug(f"→ IMEI ({len(pkt)} B): {pkt.hex().upper()}")
        self.sock.sendall(pkt)
        try:
            resp = recv_exact(self.sock, 1)
        except Exception as exc:
            log.error(f"No IMEI response: {exc}")
            return False
        if not resp:
            log.error("Server closed connection immediately (no IMEI response byte)")
            return False
        if resp == b"\x01":
            log.info("← IMEI accepted (0x01)")
            return True
        log.warning(f"← Unexpected IMEI response: 0x{resp.hex().upper()}")
        return False

    # ── AVL data send ─────────────────────────────────────────────────────────

    def send_avl(self, records: list) -> int:
        """
        Send one AVL packet. Returns ACK record count, or -1 on failure.
        """
        pkt = build_packet(self.codec, records)
        dump_packet_info(self.codec, pkt, len(records))

        log.info(
            f"→ CODEC {self.codec.upper()} | {len(records)} record(s) | {len(pkt)} bytes"
        )
        log.debug(f"   HEX: {pkt.hex().upper()}")

        try:
            self.sock.sendall(pkt)
        except OSError as exc:
            log.error(f"Send error: {exc}")
            return -1

        try:
            raw = recv_exact(self.sock, self.ack_size)
        except socket.timeout:
            log.error(
                "Timeout waiting for ACK.\n"
                "  Hint: server may have rejected the packet silently.\n"
                "  Try --reconnect or check server logs."
            )
            return -1
        except ConnectionError as exc:
            log.error(
                f"Connection closed while reading ACK: {exc}\n"
                "  Possible causes:\n"
                "    [1] CRC mismatch — server rejected the packet\n"
                "    [2] Server closes per packet → use --reconnect\n"
                "    [3] Server sends 1-byte ACK → use --ack-size 1\n"
                "    [4] Structural error — run --mode print to inspect hex"
            )
            return -1

        if not raw:
            log.error(
                "Server closed the connection without sending an ACK.\n"
                "  Possible causes:\n"
                "    [1] CRC mismatch — server rejected the packet\n"
                "    [2] Server closes per packet → use --reconnect\n"
                "    [3] Server sends 1-byte ACK → use --ack-size 1\n"
                "    [4] IO element format mismatch — run --mode print to inspect"
            )
            return -1

        ack = struct.unpack(">I", raw)[0] if self.ack_size == 4 else raw[0]
        log.info(
            f"← ACK: {ack} record(s) confirmed | raw: {raw.hex().upper()}"
        )
        return ack

    # ── full cycle (handles reconnect mode) ───────────────────────────────────

    def send_packet_cycle(self, records: list) -> int:
        if self.reconnect:
            self.connect()
            if not self.send_imei():
                self.close()
                return -1
            ack = self.send_avl(records)
            self.close()
            return ack
        return self.send_avl(records)


# ─────────────────────────────────────────────────────────────────────────────
# Print mode — generate hex dump, no TCP
# ─────────────────────────────────────────────────────────────────────────────

def run_print(codec: str, args) -> None:
    log.info(f"Generating {args.count} AVL record(s) for CODEC {codec.upper()} ...")

    records = build_avl_records(codec, args, args.lat, args.lng)
    pkt     = build_packet(codec, records)

    print_packet_dump(codec, pkt, len(records), args.imei)

    if args.output:
        with open(args.output, "wb") as f:
            f.write(pkt)
        log.info(f"Raw bytes written to {args.output}")


# ─────────────────────────────────────────────────────────────────────────────
# Send mode — live TCP
# ─────────────────────────────────────────────────────────────────────────────

def run_send(codec: str, args) -> None:
    client = TeltonikaClient(
        host      = args.host,
        port      = args.port,
        imei      = args.imei,
        codec     = codec,
        timeout   = args.timeout,
        reconnect = args.reconnect,
        ack_size  = args.ack_size,
    )

    if not args.reconnect:
        client.connect()
        if not client.send_imei():
            log.error("IMEI rejected — aborting.")
            client.close()
            sys.exit(1)

    sent         = 0
    failures     = 0
    MAX_FAILURES = 5

    # Track current position; update it between packets to simulate movement
    cur_lat = args.lat
    cur_lng = args.lng

    try:
        while True:
            log.info(
                f"─── Packet #{sent+1} | CODEC {codec.upper()} | "
                f"{args.count} records | pos=({cur_lat:.5f},{cur_lng:.5f}) ───"
            )

            records = build_avl_records(codec, args, cur_lat, cur_lng)
            ack     = client.send_packet_cycle(records)

            if ack < 0:
                failures += 1
                log.warning(f"Failure {failures}/{MAX_FAILURES}")
                if failures >= MAX_FAILURES:
                    log.error("Too many consecutive failures — stopping.")
                    break
                if not args.reconnect:
                    log.info("Attempting reconnect ...")
                    client.close()
                    try:
                        client.connect()
                        if not client.send_imei():
                            log.error("Reconnect IMEI rejected.")
                            break
                        failures = 0
                    except Exception as exc:
                        log.error(f"Reconnect failed: {exc}")
                        time.sleep(2)
                continue

            failures = 0
            sent    += 1

            # Advance position: use last record's location as new base
            last_pos = generate_positions(cur_lat, cur_lng, args.count, args.speed, args.angle)
            if last_pos:
                cur_lat, cur_lng, _, _ = last_pos[-1]

            log.info(
                f"✓ Packet #{sent} sent successfully | ACK={ack} | "
                f"new pos=({cur_lat:.5f},{cur_lng:.5f})"
            )

            if args.packets > 0 and sent >= args.packets:
                log.info(f"Packet limit reached ({args.packets}). Done.")
                break

            log.info(f"Waiting {args.interval}ms before next packet ...")
            time.sleep(args.interval / 1000.0)

    except KeyboardInterrupt:
        log.info("Stopped by user (Ctrl-C).")
    finally:
        client.close()
        log.info(f"Session complete. Packets sent: {sent}")


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

BANNER = """
╔═══════════════════════════════════════════════════════════════════╗
║          Teltonika AVL Simulator — CODEC 8 & CODEC 8E            ║
╠═══════════════════════════════════════════════════════════════════╣
║  --codec 8   : CODEC 8  (1-byte IO IDs, standard)                ║
║  --codec 8e  : CODEC 8E (2-byte IO IDs, extended)                ║
║  --mode print: hex dump, no TCP                                   ║
║  --mode send : connect and transmit to TCP server                 ║
╚═══════════════════════════════════════════════════════════════════╝
"""


def main():
    p = argparse.ArgumentParser(
        description  = "Teltonika CODEC 8 / 8E AVL TCP simulator",
        formatter_class = argparse.ArgumentDefaultsHelpFormatter,
        epilog = (
            "Examples:\n"
            "  python teltonika_simulator.py --codec 8e --mode print --count 5\n"
            "  python teltonika_simulator.py --codec 8  --mode print --count 5\n"
            "  python teltonika_simulator.py --codec 8e --mode send --host 127.0.0.1 --port 8888 --count 5\n"
            "  python teltonika_simulator.py --codec 8  --mode send --host 127.0.0.1 --port 8888 --reconnect\n"
        ),
    )

    # ── Codec & mode ──────────────────────────────────────────────────────────
    p.add_argument(
        "--codec", choices=[CODEC_8, CODEC_8E], default=CODEC_8E,
        metavar="CODEC",
        help="Which codec to use: '8' = CODEC 8, '8e' = CODEC 8E  (default: 8e)",
    )
    p.add_argument(
        "--mode", choices=["print", "send"], default="print",
        help="'print' = hex dump only (no TCP); 'send' = connect & transmit",
    )

    # ── Server ────────────────────────────────────────────────────────────────
    p.add_argument("--host",    default="127.0.0.1", help="TCP server host/IP")
    p.add_argument("--port",    type=int,   default=8888,  help="TCP server port")
    p.add_argument("--timeout", type=float, default=10.0,  help="Socket timeout (s)")

    # ── Connection behaviour ──────────────────────────────────────────────────
    p.add_argument(
        "--reconnect", action="store_true", default=False,
        help=(
            "Re-connect + re-send IMEI before every AVL packet. "
            "Use when server closes the connection after each packet."
        ),
    )
    p.add_argument(
        "--ack-size", type=int, default=4, choices=[1, 4],
        metavar="BYTES",
        help="Expected ACK size: 4=standard Teltonika (big-endian int), 1=custom server",
    )

    # ── Device identity ───────────────────────────────────────────────────────
    p.add_argument("--imei", default="353201350644990", help="15-digit device IMEI")

    # ── GPS parameters ────────────────────────────────────────────────────────
    p.add_argument("--lat",        type=float, default=54.687157,
                   help="Starting latitude (decimal degrees)")
    p.add_argument("--lng",        type=float, default=25.279652,
                   help="Starting longitude (decimal degrees)")
    p.add_argument("--alt",        type=int,   default=150,  help="Altitude (m)")
    p.add_argument("--angle",      type=int,   default=45,
                   help="Initial heading angle (0-360°, 0=North, 90=East)")
    p.add_argument("--speed",      type=int,   default=60,   help="Speed (km/h)")
    p.add_argument("--satellites", type=int,   default=9,    help="Satellite count")
    p.add_argument("--priority",   type=int,   default=0, choices=[0, 1, 2],
                   help="Record priority (0=low, 1=high, 2=panic)")

    # ── Packet parameters ─────────────────────────────────────────────────────
    p.add_argument(
        "--count", type=int, default=DEFAULT_RECORDS,
        help=f"AVL records per packet (default: {DEFAULT_RECORDS})",
    )
    p.add_argument(
        "--packets", type=int, default=0,
        help="Number of packets to send in send mode (0 = unlimited)",
    )
    p.add_argument(
        "--interval", type=int, default=5000,
        help="Interval between packets in ms (send mode only)",
    )

    # ── Output ────────────────────────────────────────────────────────────────
    p.add_argument("--output", default="", help="Write raw packet bytes to this file")

    args = p.parse_args()

    # normalise codec string
    codec = args.codec.lower()

    print(BANNER)
    print(f"  Codec    : CODEC {codec.upper()}  (ID = 0x{CODEC_ID[codec]:02X})")
    print(f"  Mode     : {args.mode}")
    print(f"  IMEI     : {args.imei}")
    print(f"  Records  : {args.count} per packet")
    if args.mode == "send":
        print(f"  Server   : {args.host}:{args.port}")
        print(f"  Reconnect: {args.reconnect}")
        print(f"  ACK size : {args.ack_size} byte(s)")
        print(f"  Packets  : {'unlimited' if args.packets == 0 else args.packets}")
        print(f"  Interval : {args.interval} ms")
    print()

    if args.mode == "print":
        run_print(codec, args)
    else:
        run_send(codec, args)


if __name__ == "__main__":
    main()