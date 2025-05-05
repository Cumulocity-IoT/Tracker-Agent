package cumulocity.microservice.tcpagent.tcp.model;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.Data;

import java.nio.ByteBuffer;
import java.util.HashMap;

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
    private HashMap<String, String> events;

    public AvlEntry(ByteBuffer dataBuffer) {
        this.instant = dataBuffer.getLong();
        this.priority = BytesUtil.toUnsigned(dataBuffer.get());
        this.longitude = dataBuffer.getInt();
        this.latitude = dataBuffer.getInt();
        this.altitude = BytesUtil.toUnsigned(dataBuffer.getShort());
        this.angle = BytesUtil.toUnsigned(dataBuffer.getShort());
        this.satellites = BytesUtil.toUnsigned(dataBuffer.get());
        this.speed = BytesUtil.toUnsigned(dataBuffer.getShort());
        this.eventID = BytesUtil.toUnsigned(dataBuffer.get());
        this.totalEvents = BytesUtil.toUnsigned(dataBuffer.get());
        this.events = new HashMap<>();
        readIO(dataBuffer, 1);
        readIO(dataBuffer, 2);
        readIO(dataBuffer, 4);
        readIO(dataBuffer, 8);
        /*
        for(int valueBytes=0;valueBytes<4;valueBytes++) {
            int eventCount = BytesUtil.toUnsigned(dataBuffer.get());
            for (int i = 0; i < eventCount; i++) {
                String key = BytesUtil.bytesToHex(new byte[]{dataBuffer.get()});
                byte[] value = new byte[1<<valueBytes];
                dataBuffer.get(value);
                events.put(key, BytesUtil.bytesToHex(value));
            }
        } */
    }

    private void readIO(ByteBuffer buffer, int size) {
        int count = buffer.get() & 0xFF;
        for (int i = 0; i < count; i++) {
            int id = buffer.get() & 0xFF;
            String key = String.valueOf(id);
            String value = switch (size) {
                case 1 -> String.valueOf(BytesUtil.toUnsigned(buffer.get()));
                case 2 -> String.valueOf(BytesUtil.toUnsigned(buffer.getShort()));
                case 4 -> String.valueOf(buffer.getInt());
                case 8 -> String.valueOf(buffer.getLong());
                default -> throw new IllegalStateException("Unexpected IO size: " + size);
            };
            events.put(key, value);
        }
    }
}
