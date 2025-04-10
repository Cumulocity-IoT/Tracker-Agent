package cumulocity.microservice.tcpagent;

import com.cumulocity.microservice.autoconfigure.MicroserviceApplication;

import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MicroserviceApplication
public class TcpAgent {
    public static void main (String[] args) {
        SpringApplication.run(TcpAgent.class, args);
    }
}