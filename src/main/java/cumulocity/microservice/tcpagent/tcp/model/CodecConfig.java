package cumulocity.microservice.tcpagent.tcp.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "codec")
public class CodecConfig {

    private String preamble;
    private String codecId;
    private String commandQuantity1;
    private String type;
    private String commandQuantity2;
    private int fixedLength;
    private int preambleSize;
    private int dataSize;
}
