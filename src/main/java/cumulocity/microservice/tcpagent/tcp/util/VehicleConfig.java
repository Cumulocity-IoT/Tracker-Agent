package cumulocity.microservice.tcpagent.tcp.util;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "vehicle")
public class VehicleConfig {

    private Map<String, String> parameters = new HashMap<>();

}
