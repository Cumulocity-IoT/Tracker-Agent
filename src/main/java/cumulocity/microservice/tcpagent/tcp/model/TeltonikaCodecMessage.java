package cumulocity.microservice.tcpagent.tcp.model;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Slf4j
@Data
public class TeltonikaCodecMessage {
    private static final int MIN_REQUIRED_BYTES = 40;

    private byte protocol;
    private byte avlDataLength;
    private AvlEntry[] avlData;

    public TeltonikaCodecMessage(byte[] data) {
        ByteBuffer dataBuffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        this.protocol = dataBuffer.get();
        this.avlDataLength = dataBuffer.get();
        int avlDataLength1 = avlDataLength & 0xFF; 
        log.info("Data length: {}", avlDataLength1);
        // Avoid creating an empty array if there's no valid data
        if (avlDataLength1 <= 0) {
            this.avlData = new AvlEntry[0];
            return; // No data to process
        }
        this.avlData = new AvlEntry[avlDataLength1];

        // Process each AvlEntry
        for (int i = 0; i < avlDataLength1; i++) {
            if (dataBuffer.remaining() < MIN_REQUIRED_BYTES) {  // Check if enough data is remaining
                log.warn("Not enough data to parse AvlEntry #{}. Remaining: {}", i, dataBuffer.remaining());
                break; // Exit the loop if insufficient data for further parsing
            }

            avlData[i] = new AvlEntry(dataBuffer, protocol);  // Populate each AvlEntry
        }
    }

}