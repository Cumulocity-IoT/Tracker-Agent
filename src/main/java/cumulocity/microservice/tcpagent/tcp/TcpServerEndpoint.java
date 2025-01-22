package cumulocity.microservice.tcpagent.tcp;

import cumulocity.microservice.tcpagent.service.CumulocityService;
import cumulocity.microservice.tcpagent.tcp.model.Codec8Message;
import cumulocity.microservice.tcpagent.tcp.model.TcpMessage;
import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        log.info("[{}] Incoming: {}", connectionID, message.getData().hashCode());

        if (message.getType() == TcpMessage.MessageType.DATA) {
            return handleDataMessage(message, connectionID);
        }

        handleDeviceRegistration(message, connectionID);
        return new byte[]{0x01};
    }

    private byte[] handleDataMessage(TcpMessage message, String connectionID) {
        Codec8Message msg = new Codec8Message(message.getData());
        String imei = GlobalConnectionStore.getConnectionRegistry().get(connectionID).getImei();

        log.info("IMEI: {}", imei);
        log.debug("Connection Repositories: connectionRegistry={}",
                GlobalConnectionStore.getConnectionRegistry());

        service.createData(msg, imei);
        log.info("Encoded data: {}", msg);
        return new byte[]{msg.getAvlDataLength()};
    }

    private void handleDeviceRegistration(TcpMessage message, String connectionID) {
        String imei = BytesUtil.fromByteArray(message.getData());
        service.createDeviceIfNotExist(imei, connectionID);
    }

    @Bean
    @ServiceActivator(inputChannel = "outboundChannel")
    public TcpSendingMessageHandler tcpSendingMessageHandler(AbstractServerConnectionFactory connectionFactory) {
        TcpSendingMessageHandler handler = new TcpSendingMessageHandler();
        handler.setConnectionFactory(connectionFactory);
        return handler;
    }
}