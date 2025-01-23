package cumulocity.microservice.tcpagent.tcp.model;

import java.nio.ByteBuffer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class Codec12Message {

    private byte[] cmd;

    public byte[] prepareCodec12Message(byte codecId, short numberOfData, String cmd) {
        byte[] commandBytes = prepareCommand(cmd);
        int totalLength = 1 + 2 + commandBytes.length + 2;

        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        buffer.put(codecId);
        buffer.putShort(numberOfData);
        buffer.put(commandBytes);

        byte[] dataForCrc = extractBytesForCrc(buffer);
        int crc16 = calculateCRC16(dataForCrc);
        buffer.putShort((short) crc16);

        this.cmd = extractFinalMessage(buffer);
        log.debug("Codec12 prepared command: {}", bytesToHex(this.cmd));

        return this.cmd;
    }

    private byte[] prepareCommand(String cmd) {
        byte[] commandBytes = cmd.getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(4 + commandBytes.length);

        buffer.putInt(4 + commandBytes.length);
        buffer.put(commandBytes);

        byte[] preparedCommand = extractFinalMessage(buffer);
        log.debug("Prepared command: {}", bytesToHex(preparedCommand));
        return preparedCommand;
    }

    private static int calculateCRC16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x01) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
            }
        }
        return crc & 0xFFFF;
    }

    public String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private byte[] extractBytesForCrc(ByteBuffer buffer) {
        byte[] dataForCrc = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(dataForCrc);
        return dataForCrc;
    }

    private byte[] extractFinalMessage(ByteBuffer buffer) {
        byte[] message = new byte[buffer.position()];
        buffer.flip();
        buffer.get(message);
        return message;
    }
}