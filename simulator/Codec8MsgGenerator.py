import struct
import binascii
import crcmod

def generate_codec8_message_with_io():
    # Header
    preamble = b'\x00\x00\x00\x00'
    codec_id = b'\x08'  # Codec 8
    num_records = b'\x01'

    # AVL Record
    timestamp = struct.pack('>Q', 1713705600000)  # ms
    priority = b'\x00'

    # GPS Data
    longitude = struct.pack('>i', 254000000)
    latitude = struct.pack('>i', 548000000)
    altitude = struct.pack('>h', 120)
    angle = struct.pack('>h', 90)
    satellites = b'\x07'
    speed = struct.pack('>H', 65)

    gps_data = longitude + latitude + altitude + angle + satellites + speed

    # IO Elements
    event_io_id = b'\x01'
    total_io_elements = b'\x06'

    # 1-byte IOs (2)
    io_1b = b'\x02' + b'\x01' + b'\x64' + b'\x02' + b'\xC8'  # ID 1:100, ID 2:200

    # 2-byte IOs (1)
    io_2b = b'\x01' + b'\x03' + struct.pack('>H', 320)  # ID 3: 320

    # 4-byte IOs (2)
    io_4b = (
        b'\x02' +
        b'\x04' + struct.pack('>i', 123456) +  # ID 4
        b'\x05' + struct.pack('>i', -321000)   # ID 5
    )

    # 8-byte IOs (1)
    io_8b = b'\x01' + b'\x06' + struct.pack('>q', 9876543210)  # ID 6

    io_block = event_io_id + total_io_elements + io_1b + io_2b + io_4b + io_8b

    avl_record = timestamp + priority + gps_data + io_block
    data_field = codec_id + num_records + avl_record + num_records
    data_length = struct.pack('>I', len(data_field))

    # Final message before CRC
    message_without_crc = preamble + data_length + data_field

    # CRC16 XMODEM
    crc16 = crcmod.predefined.mkPredefinedCrcFun('xmodem')
    crc = struct.pack('>I', crc16(message_without_crc[8:]))

    final_message = message_without_crc + crc
    base64Msg=binascii.b2a_base64(final_message).decode().strip()
    print("Hex:", final_message.hex())
    print("Base64:", base64Msg)
    return base64Msg
