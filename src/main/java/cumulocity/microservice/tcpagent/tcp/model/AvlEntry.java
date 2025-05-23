package cumulocity.microservice.tcpagent.tcp.model;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.nio.ByteOrder;

@Slf4j
@Data
public class AvlEntry {

    private long instant;
    private int priority;
    private int longitude;
    private int latitude;
    private int altitude;
    private int angle;
    private int satellites;
    private int speed;
    private int eventID;
    private int totalEvents;
    private Map<String, String> events = new HashMap<>();

    public AvlEntry(ByteBuffer buffer, byte codecId) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        
            this.instant = buffer.getLong();
            this.priority = BytesUtil.toUnsigned(buffer.get());
            this.longitude = buffer.getInt();
            this.latitude = buffer.getInt();
            this.altitude = BytesUtil.toUnsigned(buffer.getShort());
            this.angle = BytesUtil.toUnsigned(buffer.getShort());
            this.satellites = BytesUtil.toUnsigned(buffer.get());
            this.speed = BytesUtil.toUnsigned(buffer.getShort());
            this.eventID = BytesUtil.toUnsigned(buffer.get());
            if((codecId & 0xFF) == 0x08) {
                this.totalEvents = BytesUtil.toUnsigned(buffer.get());
            }

            
                for (int size : new int[]{1, 2, 4, 8}) {
                     try {
                        readIO(buffer, size);
                    } catch (Exception e) {
                        log.warn("Failed to parse IO elements of size {}: {}", size, e.getMessage());
                        break;
                    }
                }

    }

    private void readIO(ByteBuffer buffer, int size) {
        ensureRemaining(buffer, 1, "IO count");

        int count = buffer.get() & 0xFF;

        for (int i = 0; i < count; i++) {
            ensureRemaining(buffer, 1 + size, "IO entry id/value (size=" + size + ")");

            int id = buffer.get() & 0xFF;
            String key = String.valueOf(id);

            String value = switch (size) {
                case 1 -> String.valueOf(BytesUtil.toUnsigned(buffer.get()));
                case 2 -> String.valueOf(BytesUtil.toUnsigned(buffer.getShort()));
                case 4 -> String.valueOf(buffer.getInt());
                case 8 -> String.valueOf(buffer.getLong());
                default -> throw new IllegalArgumentException("Unsupported IO value size: " + size);
            };

            events.put(key, value);
        }
    }

    private void ensureRemaining(ByteBuffer buffer, int required, String context) {
        if (buffer.remaining() < required) {
            throw new IllegalStateException("Not enough data to read " + context + ". Required: " + required + ", Remaining: " + buffer.remaining());
        }
    }
}

