package cumulocity.microservice.tcpagent.tcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.ip.IpHeaders;
import org.springframework.integration.ip.tcp.TcpInboundGateway;
import org.springframework.integration.ip.tcp.connection.*;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;

import cumulocity.microservice.tcpagent.tcp.model.TCPConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.util.ConfigProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableIntegration
@IntegrationComponentScan
public class TcpServerSocketConfiguration {

    private static final CustomSerializerDeserializer SERIALIZER = new CustomSerializerDeserializer();

    private final ApplicationEventPublisher publisher;

    private final ConfigProperties config;

    @Value("${tcp.port:8888}")
    private int port;

    public TcpServerSocketConfiguration(ApplicationEventPublisher publisher, ConfigProperties config) {
        this.publisher = publisher;
        this.config=config;
    }

    @Bean
    public AbstractServerConnectionFactory serverConnectionFactory() {
        TcpNioServerConnectionFactory serverConnectionFactory = new TcpNioServerConnectionFactory(port);
        serverConnectionFactory.setUsingDirectBuffers(false);
        serverConnectionFactory.setDeserializer(SERIALIZER);
        serverConnectionFactory.setSingleUse(false);
        serverConnectionFactory.setSoTimeout(config.getConnectionTimeout());
        serverConnectionFactory.setApplicationEventPublisher(publisher);
        return serverConnectionFactory;
    }

    @Bean
    @Primary
    public MessageChannel inboundChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel outboundChannel() {
        return new DirectChannel();
    }

    public void sendCommandToConnection(String connectionId, byte[] command) {
        MessageChannel outboundChannel = outboundChannel();
        outboundChannel.send(
            MessageBuilder.withPayload(command)
                .setHeader(IpHeaders.CONNECTION_ID, connectionId)
                .build()
        );
        log.info("Command sent to connection {}", connectionId);
    }

    @Bean
    public TcpInboundGateway inboundGateway(AbstractServerConnectionFactory serverConnectionFactory,
                                            MessageChannel inboundChannel) {
        TcpInboundGateway tcpInboundGateway = new TcpInboundGateway();
        tcpInboundGateway.setConnectionFactory(serverConnectionFactory);
        tcpInboundGateway.setRequestChannel(inboundChannel);
        return tcpInboundGateway;
    }

    @EventListener
    public void handleConnectionOpen(TcpConnectionOpenEvent event) {
        log.info("New Device connected: {}", event.getConnectionId());
        TcpConnection connection = (TcpConnection) event.getSource();
        if(connection.isOpen()){
            GlobalConnectionStore.getConnectionRegistry().put(event.getConnectionId(), new TCPConnectionInfo(null, connection));
            log.info("Updated Connection Registry Maps with new connection: {}", connection.toString());
        }
    }

    public void handleConnectionClose(TcpConnectionCloseEvent event) {
        var connectionRegistry = GlobalConnectionStore.getConnectionRegistry();
        var connection = connectionRegistry.get(event.getConnectionId());
    
        if (connection == null || connection.getTcpConnection().isOpen()) {
            return; // Exit early if connection is already closed or null
        }
    
        // Safely update the connection mapping using computeIfPresent()
        GlobalConnectionStore.getImeiToConn().computeIfPresent(connection.getImei(), (imei, conn) -> {
            conn.setConnectionId(null);
            return conn;
        });
    
        connectionRegistry.remove(event.getConnectionId());
    
        log.info("Closed & Removed Device connection from repo: {}", event.getConnectionId());
    }
    

}
