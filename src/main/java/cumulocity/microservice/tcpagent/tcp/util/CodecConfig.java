package cumulocity.microservice.tcpagent.tcp.util;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "codec")
public class CodecConfig {

    private String preamble;
    private String codecId;
    private String commandQuantity1;
    private String type;
    private String commandQuantity2;
    private int fixedLength;
    private int preambleLength;
    private int dataLength;
    private int crcLength;
}
