package cumulocity.microservice.tcpagent.tcp.model;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

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

        boolean isExt = (codecId & 0xFF) == 0x8E;

        // --- GPS DATA ---
        ensureRemaining(buffer, 8, "timestamp");
        this.instant = buffer.getLong();

        ensureRemaining(buffer, 1, "priority");
        this.priority = BytesUtil.toUnsigned(buffer.get());

        ensureRemaining(buffer, 4, "longitude");
        this.longitude = buffer.getInt();

        ensureRemaining(buffer, 4, "latitude");
        this.latitude = buffer.getInt();

        ensureRemaining(buffer, 2, "altitude");
        this.altitude = BytesUtil.toUnsigned(buffer.getShort());

        ensureRemaining(buffer, 2, "angle");
        this.angle = BytesUtil.toUnsigned(buffer.getShort());

        ensureRemaining(buffer, 1, "satellites");
        this.satellites = BytesUtil.toUnsigned(buffer.get());

        ensureRemaining(buffer, 2, "speed");
        this.speed = BytesUtil.toUnsigned(buffer.getShort());

        // --- EVENT INFO ---
        if (isExt) {
            ensureRemaining(buffer, 2, "eventID (8E)");
            this.eventID = BytesUtil.toUnsigned(buffer.getShort());

            ensureRemaining(buffer, 2, "totalEvents (8E)");
            this.totalEvents = BytesUtil.toUnsigned(buffer.getShort());
        } else {
            ensureRemaining(buffer, 1, "eventID (8)");
            this.eventID = BytesUtil.toUnsigned(buffer.get());

            ensureRemaining(buffer, 1, "totalEvents (8)");
            this.totalEvents = BytesUtil.toUnsigned(buffer.get());
        }

        // --- IO ELEMENTS ---
        for (int size : new int[]{1, 2, 4, 8}) {
            readIO(buffer, size, isExt);
        }

        // --- VARIABLE LENGTH IO (ONLY 8E) ---
        if (isExt) {
            readVariableLengthIO(buffer);
        }
    }

    /**
     * Reads fixed-size IO elements (1,2,4,8 byte values)
     */
    private void readIO(ByteBuffer buffer, int valueSize, boolean isExt) {

        if (buffer.remaining() <= 0) {
            return;
        }

        int count;

        if (isExt) {
            ensureRemaining(buffer, 2, "IO count (8E)");
            count = BytesUtil.toUnsigned(buffer.getShort());
        } else {
            ensureRemaining(buffer, 1, "IO count (8)");
            count = BytesUtil.toUnsigned(buffer.get());
        }

        for (int i = 0; i < count; i++) {

            int id;

            if (isExt) {
                ensureRemaining(buffer, 2, "IO id (8E)");
                id = BytesUtil.toUnsigned(buffer.getShort());
            } else {
                ensureRemaining(buffer, 1, "IO id (8)");
                id = BytesUtil.toUnsigned(buffer.get());
            }

            ensureRemaining(buffer, valueSize, "IO value");

            String value = switch (valueSize) {
                case 1 -> String.valueOf(BytesUtil.toUnsigned(buffer.get()));
                case 2 -> String.valueOf(BytesUtil.toUnsigned(buffer.getShort()));
                case 4 -> String.valueOf(buffer.getInt());
                case 8 -> String.valueOf(buffer.getLong());
                default -> throw new IllegalArgumentException("Unsupported IO value size: " + valueSize);
            };

            events.put(String.valueOf(id), value);
        }
    }

    /**
     * Reads variable-length IO block (Codec 8E only)
     *
     * Structure:
     * NX (2 bytes)
     *   ID (2 bytes)
     *   Length (2 bytes)
     *   Value (Length bytes)
     */
    private void readVariableLengthIO(ByteBuffer buffer) {

        if (buffer.remaining() <= 0) {
            return;
        }

        ensureRemaining(buffer, 2, "NX count");
        int count = BytesUtil.toUnsigned(buffer.getShort());

        for (int i = 0; i < count; i++) {

            ensureRemaining(buffer, 4, "Variable IO header");

            int id = BytesUtil.toUnsigned(buffer.getShort());
            int length = BytesUtil.toUnsigned(buffer.getShort());

            ensureRemaining(buffer, length, "Variable IO value");

            byte[] valueBytes = new byte[length];
            buffer.get(valueBytes);

            events.put(String.valueOf(id), BytesUtil.bytesToHex(valueBytes));
        }
    }

    /**
     * Safe remaining check
     */
    private void ensureRemaining(ByteBuffer buffer, int required, String context) {
        if (buffer.remaining() < required) {
            throw new IllegalStateException(
                    "Not enough data to read " + context +
                            ". Required: " + required +
                            ", Remaining: " + buffer.remaining()
            );
        }
    }
}