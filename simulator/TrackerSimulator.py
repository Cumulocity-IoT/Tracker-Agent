import base64
import socket
import struct
import binascii
from Codec8eMsgGenerator import generate_codec8e_message_with_io
from Codec8MsgGenerator import generate_codec8_message_with_io

# Constants
SERVER_HOST = "localhost"
SERVER_PORT = 8888

# Message constants
MESSAGES = {
    "message_hex6": "000F333533323031333530363434393930",
    "msg1": base64.b64encode(bytes.fromhex(
        "00000000000001ed080700000193e38ae1700017652bab0ce1a19e00480157100009000e06ef01f0011504c8004501010106b5000cb60006423849430f9b44000009000002f10000a41310044e4c5e0000000193e38ae5580017652b040ce1a2770048013f100008000e06ef01f0011504c8004501010106b5000cb6000642384c430f9b44000009000002f10000a41310044e4c610000000193e38ae9400017652a2c0ce1a2db00480128100007000e06ef01f0011504c8004501010106b5000cb6000642385b430f9b44000009000002f10000a41310044e4c610000000193e38b1050001765289b0ce1a392004801270f0000ef0e06ef00f0001504c8004501010006b50009b60006423451430f9b44000009000002f10000a41310044e4c670000000193e38b1438001765289b0ce1a392004801270f0000f00e06ef00f0001504c8004501010006b50009b60006423451430f9b44000009000002f10000a41310044e4c670000000193e3c21e10001765289b0ce1a392004801270f0000000e06ef00f0001504c8004501010006b5000cb600064232ca430f9b44000009000002f10000a41310044e4c670000000193e3f92400001765289b0ce1a39200480127120000000e06ef00f0001504c8004501010006b5000cb600054232b3430f9b44000009000002f10000a41310044e4c67000700004a35"
    )).decode(),
    "msg2": base64.b64encode(bytes.fromhex(
        "00000000000000d5080300000193e43e24100017652b9a0ce1a2db004400000e0000000e06ef00f0001504c8004501010106b5000ab60006423294430fa044000009000002f10000a41310044e4c670000000193e43e2be00017652b9a0ce1a2db004400000e0000ef0e06ef01f0011504c8004501010106b5000ab60006423249430fa044000009000002f10000a41310044e4c670000000193e43e2bea0017652b9a0ce1a2db004400000e0000f00e06ef01f0011504c8004501010106b5000ab60006423249430fa044000009000002f10000a41310044e4c67000300007f5c"
    )).decode(),
    "msg3": "MDAwMDAwMDAwMDAwMDA4YzA4MDEwMDAwMDEzZmViNTVmZjc0MDAwZjBlYTg1MDIwOWE2OTAwMDA5NDAwMDAxMjAwMDAwMDFlMDkwMTAwMDIwMDAzMDAwNDAwMTYwMTQ3MDNmMDAwMTUwNGM4MDAwYzA5MDA3MzBhMDA0NjBiMDA1MDEzMDA0NjQzMDZkNzQ0MDAwMGI1MDAwYmI2MDAwNzQyMmU5ZjE4MDAwMGNkMDM4NmNlMDAwMTA3YzcwMDAwMDAwMGYxMDAwMDYwMWE0NjAwMDAwMTM0NDgwMDAwMGJiODQ5MDAwMDBiYjg0YTAwMDAwYmI4NGMwMDAwMDAwMDAyNGUwMDAwMDAwMDAwMDAwMDAwY2YwMDAwMDAwMDAwMDAwMDAwMDEwMDAwM2ZjYQo=",
    "msg4": "0000000000000193080100000193de9797a800196560040a5388ca0044009613004c001207ef01f00150011504c8004501010108b50004b60002426fc318004c430f7544000009055619000001c70002095c020b00000002183d5f1d0e0000000042c334c101bea7",
    "msg5": "AAAAAAAAAGoIAQAAAZYPDIGAABmuuw4PKQrwAu8BZhAAXQAVBu8B8AEVBAEBswBxYwtCbiIYAF1DEBVEAAAJAysZC7gaC7gbC7hWC7hoC7hqC7gC8QAApBMQAknlOgILAAAAAhg9Xx0OAAAAAEL2jUQBAAB6yg==",
    "msg6": "AAAAAAAAACMIAQAAAY8A0EQAAA8y/cAiRc3AAPoAWggAPAEBAQr/AAAAAQAA4Ms=",
    "msg7": "AAAAAAAAACaOAQAAAY8A0EQAAA8ju4AgqdEAAGQAeAoALQEBAAABDwC8YU4AAQAAMvc=",
    "msg8": "000000000000007D8E01000001941F2D258000176817E90CDF7F44004301660E00110000001A000700EF0100150200450300010400020500030600B407000500420A0B00090C0D00110E0F0012101100131213000700C7000A0B0C0D00100E0F10110048FFF0BDC0004916171819004AFFE1DC0001AF0BDC0001A0009FBF1004A001E8480010000FE6"
}

def send_message(sock, message_hex):
    byte_array = bytes.fromhex(message_hex)
    sock.sendall(byte_array)
    print(f"Sent Codec 8 hexstring message: {byte_array.hex()}")
    response = sock.recv(1024)
    print(f"Received response: {response.hex()}")
    print(parse_codec_12_message(response))

def simulate_teltonika_device(server_host, server_port, message_hex, base64_msg=None):
    try:
        with socket.create_connection((server_host, server_port)) as sock:
            print(f"Connected to {server_host}:{server_port}")
            send_message(sock, message_hex)

            if base64_msg:
                decoded_bytes = base64.b64decode(base64_msg)
                sock.sendall(decoded_bytes)
                print(f"Sent base64 decoded message: {decoded_bytes.hex()}")
                response = sock.recv(1024)
                print(f"Received response: {response.hex()}")
    except Exception as e:
        print(f"Error: {e}")

def parse_codec_12_message(byte_array):
    try:
        if len(byte_array) < 12:
            raise ValueError("Message is too short to be valid.")
        preamble = byte_array[:4]
        data_field_length = struct.unpack(">H", byte_array[4:6])[0]
        codec_id = byte_array[6]
        num_records = byte_array[7]
        data_records = byte_array[8:8+data_field_length]
        crc_received = struct.unpack(">I", byte_array[8+data_field_length:12+data_field_length])[0]
        crc_calculated = binascii.crc32(byte_array[:8+data_field_length]) & 0xFFFFFFFF

        return {
            "preamble": preamble.hex(),
            "data_field_length": data_field_length,
            "codec_id": codec_id,
            "num_records": num_records,
            "data_records": data_records.hex(),
            "crc_received": crc_received,
            "crc_valid": crc_received == crc_calculated,
        }
    except Exception as e:
        return {"error": str(e)}

if __name__ == "__main__":
    simulate_teltonika_device(SERVER_HOST, SERVER_PORT, MESSAGES["message_hex6"], MESSAGES["msg1"])
    simulate_teltonika_device(SERVER_HOST, SERVER_PORT, MESSAGES["message_hex6"], generate_codec8e_message_with_io())
