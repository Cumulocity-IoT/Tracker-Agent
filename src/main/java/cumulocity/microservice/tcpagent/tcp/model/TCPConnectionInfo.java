package cumulocity.microservice.tcpagent.tcp.model;

import org.springframework.integration.ip.tcp.connection.TcpConnection;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TCPConnectionInfo {

    private String imei;
    
    private TcpConnection tcpConnection;
    
}
