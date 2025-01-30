package cumulocity.microservice.tcpagent.tcp.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import cumulocity.microservice.tcpagent.tcp.util.CodecConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Data
public class Codec12Message {

    public  final byte[] command;

    public Codec12Message(String cmd, CodecConfig codecConfig) {
        byte[] commandData = cmd.getBytes(StandardCharsets.UTF_8);

        // Calculate sizes
        int commandSize = commandData.length;
        int PayloadSize = codecConfig.getFixedLength() + commandSize;
        
        ByteBuffer finalMessage = ByteBuffer.allocate(
                codecConfig.getPreambleLength() + codecConfig.getDataLength() + PayloadSize + codecConfig.getCrcLength() // Preamble + Data Size + Payload + CRC
        );

        // Preamble
        finalMessage.put(BytesUtil.hexToByteArray(codecConfig.getPreamble()));

        // Data Size
        finalMessage.putInt(PayloadSize);

        // Payload Construction (Codec ID, Command Quantity, Type, Command Size, Command, Command Quantity 2)
        finalMessage.put(BytesUtil.hexToByte(codecConfig.getCodecId()));
        finalMessage.put(BytesUtil.hexToByte(codecConfig.getCommandQuantity1()));
        finalMessage.put(BytesUtil.hexToByte(codecConfig.getType()));
        finalMessage.putInt(commandSize);
        finalMessage.put(commandData);
        finalMessage.put(BytesUtil.hexToByte(codecConfig.getCommandQuantity2()));

        // Calculate CRC-16
        byte[] crcInput = finalMessage.array();
        int crc16 = BytesUtil.calculateCRC16(crcInput);

        // Append CRC in little-endian format
        finalMessage.put((byte) (crc16 & 0xFF));       // Low byte
        finalMessage.put((byte) ((crc16 >> 8) & 0xFF)); // High byte

        this.command = finalMessage.array();
        log.info("Prepared Codec12 message: {}", BytesUtil.bytesToHex(this.command));
    }
}
