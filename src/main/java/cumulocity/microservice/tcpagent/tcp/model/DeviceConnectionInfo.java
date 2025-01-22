package cumulocity.microservice.tcpagent.tcp.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DeviceConnectionInfo {

    private String connectionId;
    
    private String imei;

    private String id;
    
}
