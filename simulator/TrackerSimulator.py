import base64
import socket
import struct
import crcmod


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
            receive_command(sock)
            
            if base64_msg:
                decoded_bytes = base64.b64decode(base64_msg)
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
    SERVER_HOST = "localhost"#"0.tcp.in.ngrok.io"  # Replace with the server's IP address
    SERVER_PORT = 8888#13563        # Replace with the server's listening port
    #SERVER_HOST = "0.tcp.in.ngrok.io"  # Replace with the server's IP address
    #SERVER_PORT = 11125     # Replace with the server's listening port

    # Sample messages
    message_hex1 = '000F333536333037303432343431303134'
    message_hex2 = '000F333536333037303432343431303133'
    message_hex3 = '000F333536333037303432343431303139'
    message_hex4='000F333533323031333530363434393333'
    base64_msg1= 'AAAAAAAAAe0IBwAAAZPjiuFwABdlK6sM4aGeAEgBVxAACQAOBu8B8AEVBMgARQEBAQa1AAy2AAZCOElDD5tEAAAJAAAC8QAApBMQBE5MXgAAAAGT44rlWAAXZSsEDOGidwBIAT8QAAgADgbvAfABFQTIAEUBAQEGtQAMtgAGQjhMQw+bRAAACQAAAvEAAKQTEAROTGEAAAABk+OK6UAAF2UqLAzhotsASAEoEAAHAA4G7wHwARUEyABFAQEBBrUADLYABkI4W0MPm0QAAAkAAALxAACkExAETkxhAAAAAZPjixBQABdlKJsM4aOSAEgBJw8AAO8OBu8A8AAVBMgARQEBAAa1AAm2AAZCNFFDD5tEAAAJAAAC8QAApBMQBE5MZwAAAAGT44sUOAAXZSibDOGjkgBIAScPAADwDgbvAPAAFQTIAEUBAQAGtQAJtgAGQjRRQw+bRAAACQAAAvEAAKQTEAROTGcAAAABk+PCHhAAF2Uomwzho5IASAEnDwAAAA4G7wDwABUEyABFAQEABrUADLYABkIyykMPm0QAAAkAAALxAACkExAETkxnAAAAAZPj+SQAABdlKJsM4aOSAEgBJxIAAAAOBu8A8AAVBMgARQEBAAa1AAy2AAVCMrNDD5tEAAAJAAAC8QAApBMQBE5MZwAHAABKNQ=='
    base64_msg2 = 'AAAAAAAAANUIAwAAAZPkPiQQABdlK5oM4aLbAEQAAA4AAAAOBu8A8AAVBMgARQEBAQa1AAq2AAZCMpRDD6BEAAAJAAAC8QAApBMQBE5MZwAAAAGT5D4r4AAXZSuaDOGi2wBEAAAOAADvDgbvAfABFQTIAEUBAQEGtQAKtgAGQjJJQw+gRAAACQAAAvEAAKQTEAROTGcAAAABk+Q+K+oAF2UrmgzhotsARAAADgAA8A4G7wHwARUEyABFAQEBBrUACrYABkIySUMPoEQAAAkAAALxAACkExAETkxnAAMAAH9c'
    
    #simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex1, base64_msg1)
    #simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex2, base64_msg2)
    simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex1, base64_msg2)
