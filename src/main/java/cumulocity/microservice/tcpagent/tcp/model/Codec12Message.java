package cumulocity.microservice.tcpagent.tcp.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class Codec12Message {

    private CodecConfig codecConfig;

    public byte[] prepareCodec12Message(String cmd) {
        // Convert command to bytes
        byte[] commandData = cmd.getBytes(StandardCharsets.UTF_8);

        // Command Size (4 bytes, length of command data in bytes)
        byte[] commandSizeBytes = ByteBuffer.allocate(codecConfig.getPreambleSize()).putInt(commandData.length).array();

        // Data Size (4 bytes)
        int dataSize = codecConfig.getFixedLength() + commandData.length;
        byte[] dataSizeBytes = ByteBuffer.allocate(codecConfig.getDataSize()).putInt(dataSize).array();

        // Construct payload (Codec ID + Command Quantity 1 + Type + Command Size + Command + Command Quantity 2)
        ByteBuffer payloadBuffer = ByteBuffer.allocate(dataSize);
        payloadBuffer.put(hexToByte(codecConfig.getCodecId()));
        payloadBuffer.put(hexToByte(codecConfig.getCommandQuantity1()));
        payloadBuffer.put(hexToByte(codecConfig.getType()));
        payloadBuffer.put(commandSizeBytes);
        payloadBuffer.put(commandData);
        payloadBuffer.put(hexToByte(codecConfig.getCommandQuantity2()));

        byte[] payload = payloadBuffer.array();

        // Calculate CRC-16
        byte[] crcInput = ByteBuffer.allocate(dataSizeBytes.length + payload.length)
                                    .put(dataSizeBytes)
                                    .put(payload)
                                    .array();
        int crc16 = calculateCRC16(crcInput);

        // Construct final message (Preamble + Data Size + Payload + CRC)
        ByteBuffer finalMessage = ByteBuffer.allocate(codecConfig.getPreambleSize()
                + dataSizeBytes.length + payload.length + 2);
        finalMessage.put(hexToByteArray(codecConfig.getPreamble()));
        finalMessage.put(dataSizeBytes);
        finalMessage.put(payload);

        // Append CRC in little-endian format
        finalMessage.put((byte) (crc16 & 0xFF));       // Low byte
        finalMessage.put((byte) ((crc16 >> 8) & 0xFF)); // High byte

        byte[] finalMsgArray = finalMessage.array();
        log.info("Prepared message with CRC: " + bytesToHex(finalMsgArray));

        return finalMsgArray;
    }

    // Converts hex string to byte
    private byte hexToByte(String hexString) {
        return (byte) Integer.parseInt(hexString, 16);
    }

    // Converts hex comma-separated string to byte array
    private byte[] hexToByteArray(String hex) {
        String[] hexValues = hex.split(",");
        byte[] byteArray = new byte[hexValues.length];
        for (int i = 0; i < hexValues.length; i++) {
            byteArray[i] = hexToByte(hexValues[i]);
        }
        return byteArray;
    }
    
    // CRC16 calculation (IBM CRC-16)
    private int calculateCRC16(byte[] data) {
        int crc = 0;
        int byteNumber = 0;

        while (byteNumber < data.length) {
            crc ^= data[byteNumber] & 0xFF;
            int bitNumber = 0;

            while (bitNumber < 8) {
                int carry = crc & 1;
                crc >>= 1;

                if (carry == 1) {
                    crc ^= 0xA001;
                }

                bitNumber++;
            }

            byteNumber++;
        }

        return crc;
    }

    // Converts byte array to hex string
    public String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
