package cumulocity.microservice.tcpagent.tcp;

import cumulocity.microservice.tcpagent.service.CumulocityService;
import cumulocity.microservice.tcpagent.tcp.model.TeltonikaCodecMessage;
import cumulocity.microservice.tcpagent.tcp.model.TcpMessage;
import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.MessageEndpoint;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.ip.IpHeaders;
import org.springframework.integration.ip.tcp.TcpSendingMessageHandler;
import org.springframework.integration.ip.tcp.connection.AbstractServerConnectionFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Configuration
@MessageEndpoint
@RequiredArgsConstructor
public class TcpServerEndpoint {

    private final CumulocityService service;

    @ServiceActivator(inputChannel = "inboundChannel")
    public byte[] process(TcpMessage message, @Header(IpHeaders.CONNECTION_ID) String connectionID) {
        log.info("[{}] Incoming: {}", connectionID, BytesUtil.bytesToHex(message.getData()));

        if (message.getType() == TcpMessage.MessageType.DATA) {
            return handleDataMessage(message, connectionID);
        }

        handleDeviceRegistration(message, connectionID);
        return new byte[]{0x01};
    }

    private byte[] handleDataMessage(TcpMessage message, String connectionID) {

        // Parse Teltonika packet
        TeltonikaCodecMessage msg = new TeltonikaCodecMessage(message.getData());

        // Extract IMEI mapped to connection
        String imei = GlobalConnectionStore
                .getConnectionRegistry()
                .get(connectionID)
                .getImei();

        if (imei == null) {
            log.warn("IMEI not found for connection {}", connectionID);
            return ByteBuffer.allocate(4).putInt(0).array(); // Send 0 ACK safely
        }

        if (GlobalConnectionStore.getImeiToConn().get(imei) == null) {
            log.warn("Tracker device not registered. Kindly register before sending data");

            // Even if not registered, MUST send ACK to avoid timeout
            int recordCount = msg.getAvlDataLength() & 0xFF; // Convert unsigned byte to int
            return ByteBuffer.allocate(4).putInt(recordCount).array();
        }

        log.info("IMEI: {}", imei);

        try {
            service.createData(msg, imei, message.getData());
        } catch (Exception ex) {
            log.error("Failed to process AVL data for IMEI {}", imei, ex);

            // If processing fails, return 0 (device will resend)
            return ByteBuffer.allocate(4).putInt(0).array();
        }

        // IMPORTANT: ACK must be 4-byte integer (big-endian)
        int recordCount = msg.getAvlDataLength() & 0xFF; // Convert unsigned byte to int

        log.info("Sending ACK for {} AVL records", recordCount);

        return ByteBuffer
                .allocate(4)
                .putInt(recordCount)
                .array();
    }

    private void handleDeviceRegistration(TcpMessage message, String connectionID) {
        String imei = BytesUtil.fromByteArray(message.getData());
        service.updateConnectionAndProcessOperations(imei, connectionID);
    }

    @Bean
    @ServiceActivator(inputChannel = "outboundChannel")
    public TcpSendingMessageHandler tcpSendingMessageHandler(AbstractServerConnectionFactory connectionFactory) {
        TcpSendingMessageHandler handler = new TcpSendingMessageHandler();
        handler.setConnectionFactory(connectionFactory);
        return handler;
    }
}