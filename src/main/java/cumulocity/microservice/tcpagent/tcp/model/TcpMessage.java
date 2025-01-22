package cumulocity.microservice.tcpagent.tcp.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TcpMessage {

    private MessageType type;
    private byte[] data;

    public enum MessageType {
        IMEI,
        DATA
    }
}
