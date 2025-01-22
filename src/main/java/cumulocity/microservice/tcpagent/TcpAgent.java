package cumulocity.microservice.tcpagent;

import com.cumulocity.microservice.autoconfigure.MicroserviceApplication;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.event.EventListener;
import org.springframework.integration.ip.tcp.connection.TcpConnectionCloseEvent;
import org.springframework.retry.annotation.EnableRetry;

@Slf4j
@EnableRetry
@MicroserviceApplication
public class TcpAgent {
    public static void main (String[] args) {
        SpringApplication.run(TcpAgent.class, args);
    }
}