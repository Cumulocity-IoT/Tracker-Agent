import base64
import socket
import struct
import crcmod
from Codec8eMsgGenerator import generate_codec8e_message_with_io
from Codec8MsgGenerator import generate_codec8_message_with_io


def send_message(sock, message):
    byte_array = bytes.fromhex(message)
    sock.sendall(byte_array)
    print(f"Sent Codec 8 hexstring message: {byte_array.hex()}")
    response = sock.recv(1024)
    print(f"Received response: {response.hex()}")
    print(parse_codec_12_message(response))


def receive_command(sock, buffer_size=1024):
    command = sock.recv(buffer_size)
    print(f"Received command: {command.hex()}")
    print(parse_codec_12_message(command))


def simulate_teltonika_device(server_host, server_port, message_hex, base64_msg=None):
    """Simulate a Teltonika device sending a Codec 8 message."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.connect((server_host, server_port))
            print(f"Connected to {server_host}:{server_port}")

            send_message(sock, message_hex)
            #receive_command(sock)
            
            if base64_msg:
                decoded_bytes = base64.b64decode(base64_msg)
                #decoded_bytes=bytes.fromhex(base64_msg)
                sock.sendall(decoded_bytes)
                print(f"Sent base64 decoded message: {decoded_bytes.hex()}")
                response = sock.recv(1024)
                print(f"Received response: {response.hex()}")
            else:
                send_message(sock, message_hex)

    except Exception as e:
        print(f"Error: {e}")

def parse_codec_12_message(byte_array):
    try:
        # Verify minimum message length
        if len(byte_array) < 12:
            raise ValueError("Message is too short to be valid.")

        # Extract Preamble (4 bytes)
        preamble = byte_array[:4]

        # Extract Data Field Length (2 bytes, Big-Endian)
        data_field_length = struct.unpack(">H", byte_array[4:6])[0]

        # Extract Codec ID (1 byte)
        codec_id = byte_array[6]

        # Extract Number of Records (1 byte)
        num_records = byte_array[7]

        # Extract Data Records (variable length based on data_field_length)
        data_records_start = 8
        data_records_end = 8 + data_field_length
        data_records = byte_array[data_records_start:data_records_end]

        # Extract CRC (4 bytes at the end)
        crc_offset = data_records_end
        crc_received = struct.unpack(">I", byte_array[crc_offset:crc_offset+4])[0]
        print("crc received: "+ str(crc_received))

        # Verify CRC (calculate and compare)
        crc_calculated = validate_crc(byte_array[:crc_offset]) & 0xFFFFFFFF
        print("crc_calculated: " +crc_calculated)
        if crc_calculated != crc_received:
            raise ValueError(f"CRC mismatch: calculated {crc_calculated}, received {crc_received}")

        # Return the parsed result as a dictionary
        return {
            "preamble": preamble.hex(),
            "data_field_length": data_field_length,
            "codec_id": codec_id,
            "num_records": num_records,
            "data_records": data_records.hex(),
            "crc_received": crc_received,
            "crc_valid": crc_calculated == crc_received,
        }

    except Exception as e:
        return {"error": str(e)}

def validate_crc(data):
    # Extract data without the CRC
    data_without_crc = data[:-2]
    # Extract the provided CRC (last two bytes)
    provided_crc = int.from_bytes(data[-2:], byteorder='big')

    # Define CRC-16 function with polynomial 0x11021 (used by Teltonika)
    crc16 = crcmod.mkCrcFun(0x11021, rev=False, initCrc=0x0000, xorOut=0x0000)
    # Calculate the CRC of the data
    calculated_crc = crc16(data_without_crc)

    # Validate CRC
    return calculated_crc == provided_crc, calculated_crc, provided_crc


# Example Usage
if __name__ == "__main__":
    #SERVER_HOST = "158.101.249.171"#"0.tcp.in.ngrok.io"  # Replace with the server's IP address
    #SERVER_PORT = 8888#13563        # Replace with the server's listening port
    SERVER_HOST = "localhost"  # Replace with the server's IP address
    SERVER_PORT = 30001     # Replace with the server's listening port

    # Sample messages
    message_hex1 = '000F333536333037303432343431303134'
    message_hex2 = '000F333536333037303432343431303133'
    message_hex3 = '000F333536333037303432343431303139'
    message_hex4 ='000F333533323031333530363434393333'
    message_hex5 ='000F333533323031333531363638353433'
    message_hex6='000F333533323031333530363434393930'
    msg1='00000000000001ed080700000193e38ae1700017652bab0ce1a19e00480157100009000e06ef01f0011504c8004501010106b5000cb60006423849430f9b44000009000002f10000a41310044e4c5e0000000193e38ae5580017652b040ce1a2770048013f100008000e06ef01f0011504c8004501010106b5000cb6000642384c430f9b44000009000002f10000a41310044e4c610000000193e38ae9400017652a2c0ce1a2db00480128100007000e06ef01f0011504c8004501010106b5000cb6000642385b430f9b44000009000002f10000a41310044e4c610000000193e38b1050001765289b0ce1a392004801270f0000ef0e06ef00f0001504c8004501010006b50009b60006423451430f9b44000009000002f10000a41310044e4c670000000193e38b1438001765289b0ce1a392004801270f0000f00e06ef00f0001504c8004501010006b50009b60006423451430f9b44000009000002f10000a41310044e4c670000000193e3c21e10001765289b0ce1a392004801270f0000000e06ef00f0001504c8004501010006b5000cb600064232ca430f9b44000009000002f10000a41310044e4c670000000193e3f92400001765289b0ce1a39200480127120000000e06ef00f0001504c8004501010006b5000cb600054232b3430f9b44000009000002f10000a41310044e4c67000700004a35'
    msg1= base64.b64encode(msg1.encode('utf-8'))
    msg2 = '00000000000000d5080300000193e43e24100017652b9a0ce1a2db004400000e0000000e06ef00f0001504c8004501010106b5000ab60006423294430fa044000009000002f10000a41310044e4c670000000193e43e2be00017652b9a0ce1a2db004400000e0000ef0e06ef01f0011504c8004501010106b5000ab60006423249430fa044000009000002f10000a41310044e4c670000000193e43e2bea0017652b9a0ce1a2db004400000e0000f00e06ef01f0011504c8004501010106b5000ab60006423249430fa044000009000002f10000a41310044e4c67000300007f5c'
    msg2 = base64.b64encode(msg2.encode('utf-8'))
    msg3 ='MDAwMDAwMDAwMDAwMDA4YzA4MDEwMDAwMDEzZmViNTVmZjc0MDAwZjBlYTg1MDIwOWE2OTAwMDA5NDAwMDAxMjAwMDAwMDFlMDkwMTAwMDIwMDAzMDAwNDAwMTYwMTQ3MDNmMDAwMTUwNGM4MDAwYzA5MDA3MzBhMDA0NjBiMDA1MDEzMDA0NjQzMDZkNzQ0MDAwMGI1MDAwYmI2MDAwNzQyMmU5ZjE4MDAwMGNkMDM4NmNlMDAwMTA3YzcwMDAwMDAwMGYxMDAwMDYwMWE0NjAwMDAwMTM0NDgwMDAwMGJiODQ5MDAwMDBiYjg0YTAwMDAwYmI4NGMwMDAwMDAwMDAyNGUwMDAwMDAwMDAwMDAwMDAwY2YwMDAwMDAwMDAwMDAwMDAwMDEwMDAwM2ZjYQo='
    msg4='0000000000000193080100000193de9797a800196560040a5388ca0044009613004c001207ef01f00150011504c8004501010108b50004b60002426fc318004c430f7544000009055619000001c70002095c020b00000002183d5f1d0e0000000042c334c101bea7'
    msg5 = 'AAAAAAAAAGoIAQAAAZYPDIGAABmuuw4PKQrwAu8BZhAAXQAVBu8B8AEVBAEBswBxYwtCbiIYAF1DEBVEAAAJAysZC7gaC7gbC7hWC7hoC7hqC7gC8QAApBMQAknlOgILAAAAAhg9Xx0OAAAAAEL2jUQBAAB6yg=='
    msg6='AAAAAAAAACMIAQAAAY8A0EQAAA8y/cAiRc3AAPoAWggAPAEBAQr/AAAAAQAA4Ms='
    msg7='AAAAAAAAACaOAQAAAY8A0EQAAA8ju4AgqdEAAGQAeAoALQEBAAABDwC8YU4AAQAAMvc='
    #simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex6, msg2)
    simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex6, generate_codec8e_message_with_io())
