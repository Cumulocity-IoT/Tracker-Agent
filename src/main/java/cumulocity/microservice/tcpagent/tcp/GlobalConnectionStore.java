package cumulocity.microservice.tcpagent.tcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import cumulocity.microservice.tcpagent.tcp.model.DeviceConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.model.TCPConnectionInfo;
import lombok.Getter;


public class GlobalConnectionStore {

    // Store active connections (Connection ID -> TcpConnection)
    @Getter
    private static final ConcurrentHashMap<String, TCPConnectionInfo> connectionRegistry = new ConcurrentHashMap<>();

    // Store IMEI mappings (IMEI -> Connection ID)
    @Getter
    private static final ConcurrentHashMap<String, DeviceConnectionInfo> imeiToConn = new ConcurrentHashMap<>();

    @Getter
    private static final List<String> tenants = new ArrayList<>();
}
