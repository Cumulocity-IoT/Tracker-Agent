package cumulocity.microservice.tcpagent.tcp.util;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "tcp-agent")
public class ConfigProperties {

    private String moType;
    private String eventTypeLocation;
    private String eventDescLocation;
    private String eventTypeTeltonika;
    private String eventDescTeltonika;
    private int bigDecimalFactor;
    
    // Static fields for environment variables
    public static final String C8Y_BOOTSTRAP_TENANT = System.getenv("C8Y_BOOTSTRAP_TENANT");
    public static String C8Y_DEFAULT_TENANT = System.getenv("C8Y_DEFAULT_TENANT");
    private final String CRON_EXPRESSION = 
    (System.getenv("C8Y_CRON_SCHEDULE") != null) ? System.getenv("C8Y_CRON_SCHEDULE") : "0 */2 * * * *";


    @Bean
    public String getCronExpression() {
        return CRON_EXPRESSION;
    }
}
