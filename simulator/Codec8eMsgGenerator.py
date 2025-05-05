import struct
import binascii
import crcmod
import random
import time

def generate_codec8e_message_with_io():
    # Header
    preamble = b'\x00\x00\x00\x00'
    codec_id = b'\x8E'  # Codec 8 Extended
    num_records = b'\x01'

    # AVL Record Fields
    timestamp = struct.pack('>Q', int(time.time() * 1000))  # Current UTC timestamp in ms
    priority = b'\x00'

    # GPS Data
    longitude = struct.pack('>i', random.randint(-180000000, 180000000))
    latitude = struct.pack('>i', random.randint(-90000000, 90000000))
    altitude = struct.pack('>h', random.randint(0, 10000))
    angle = struct.pack('>h', random.randint(0, 359))
    satellites = bytes([random.randint(0, 15)])
    speed = struct.pack('>H', random.randint(0, 250))

    gps_data = longitude + latitude + altitude + angle + satellites + speed

    # IO Element
    event_io_id = b'\x01'
    total_io = b'\x0A'  # 10 IO elements in total

    # 1-byte IOs (4)
    io_1b = (
        b'\x04' +
        b'\x01' + bytes([random.randint(0, 255)]) +
        b'\x02' + bytes([random.randint(0, 255)]) +
        b'\x07' + bytes([random.randint(0, 255)]) +
        b'\x08' + bytes([random.randint(0, 255)])
    )

    # 2-byte IOs (2)
    io_2b = (
        b'\x02' +
        b'\x03' + struct.pack('>H', random.randint(0, 65535)) +
        b'\x09' + struct.pack('>H', random.randint(0, 65535))
    )

    # 4-byte IOs (3)
    io_4b = (
        b'\x03' +
        b'\x04' + struct.pack('>I', random.randint(0, 2**32 - 1)) +
        b'\x05' + struct.pack('>I', random.randint(0, 2**32 - 1)) +
        b'\x0A' + struct.pack('>I', random.randint(0, 2**32 - 1))
    )

    # 8-byte IOs (1)
    io_8b = (
        b'\x01' +
        b'\x06' + struct.pack('>Q', random.randint(0, 2**64 - 1))
    )

    io_data = event_io_id + total_io + io_1b + io_2b + io_4b + io_8b

    avl_record = timestamp + priority + gps_data + io_data

    data_field = codec_id + num_records + avl_record + num_records
    data_length = struct.pack('>I', len(data_field))
    message_without_crc = preamble + data_length + data_field

    # CRC16-XMODEM
    crc16 = crcmod.predefined.mkPredefinedCrcFun('xmodem')
    crc = struct.pack('>I', crc16(message_without_crc[8:]))

    full_message = message_without_crc + crc
    base64encoded = binascii.b2a_base64(full_message).decode().strip()

    print("HEX     :", full_message.hex())
    print("Base64  :", base64encoded)
    return base64encoded