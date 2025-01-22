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
        for(int valueBytes=0;valueBytes<4;valueBytes++) {
            int eventCount = BytesUtil.toUnsigned(dataBuffer.get());
            for (int i = 0; i < eventCount; i++) {
                String key = BytesUtil.bytesToHex(new byte[]{dataBuffer.get()});
                byte[] value = new byte[1<<valueBytes];
                dataBuffer.get(value);
                events.put(key, BytesUtil.bytesToHex(value));
            }
        }
    }
}
