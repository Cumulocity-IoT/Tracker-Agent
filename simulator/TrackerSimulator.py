import base64
import socket


def send_message(sock, message):
    byte_array = bytes.fromhex(message)
    sock.sendall(byte_array)
    print(f"Sent Codec 8 hexstring message: {byte_array.hex()}")
    response = sock.recv(1024)
    print(f"Received response: {response.hex()}")


def receive_command(sock, buffer_size=1024):
    command = sock.recv(buffer_size)
    print(f"Received command: {command.hex()}")


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


# Example Usage
if __name__ == "__main__":
    SERVER_HOST = "0.tcp.in.ngrok.io"  # Replace with the server's IP address
    SERVER_PORT = 16676         # Replace with the server's listening port

    # Sample messages
    message_hex1 = '000F333536333037303432343431303134'
    message_hex2 = '000F333536333037303432343431303133'
    message_hex3 = '000F333536333037303432343431303135'
    base64_msg1= 'AAAAAAAAAe0IBwAAAZPjiuFwABdlK6sM4aGeAEgBVxAACQAOBu8B8AEVBMgARQEBAQa1AAy2AAZCOElDD5tEAAAJAAAC8QAApBMQBE5MXgAAAAGT44rlWAAXZSsEDOGidwBIAT8QAAgADgbvAfABFQTIAEUBAQEGtQAMtgAGQjhMQw+bRAAACQAAAvEAAKQTEAROTGEAAAABk+OK6UAAF2UqLAzhotsASAEoEAAHAA4G7wHwARUEyABFAQEBBrUADLYABkI4W0MPm0QAAAkAAALxAACkExAETkxhAAAAAZPjixBQABdlKJsM4aOSAEgBJw8AAO8OBu8A8AAVBMgARQEBAAa1AAm2AAZCNFFDD5tEAAAJAAAC8QAApBMQBE5MZwAAAAGT44sUOAAXZSibDOGjkgBIAScPAADwDgbvAPAAFQTIAEUBAQAGtQAJtgAGQjRRQw+bRAAACQAAAvEAAKQTEAROTGcAAAABk+PCHhAAF2Uomwzho5IASAEnDwAAAA4G7wDwABUEyABFAQEABrUADLYABkIyykMPm0QAAAkAAALxAACkExAETkxnAAAAAZPj+SQAABdlKJsM4aOSAEgBJxIAAAAOBu8A8AAVBMgARQEBAAa1AAy2AAVCMrNDD5tEAAAJAAAC8QAApBMQBE5MZwAHAABKNQ=='
    base64_msg2 = 'AAAAAAAAANUIAwAAAZPkPiQQABdlK5oM4aLbAEQAAA4AAAAOBu8A8AAVBMgARQEBAQa1AAq2AAZCMpRDD6BEAAAJAAAC8QAApBMQBE5MZwAAAAGT5D4r4AAXZSuaDOGi2wBEAAAOAADvDgbvAfABFQTIAEUBAQEGtQAKtgAGQjJJQw+gRAAACQAAAvEAAKQTEAROTGcAAAABk+Q+K+oAF2UrmgzhotsARAAADgAA8A4G7wHwARUEyABFAQEBBrUACrYABkIySUMPoEQAAAkAAALxAACkExAETkxnAAMAAH9c'
    
    #simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex1, base64_msg1)
    #simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex2, base64_msg2)
    simulate_teltonika_device(SERVER_HOST, SERVER_PORT, message_hex3, base64_msg2)
