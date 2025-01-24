package cumulocity.microservice.tcpagent.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tcp-agent")
public class ConfigProperties {

    private String idType;
    private String moType;
    private String moNamePrefix;
    private int c8yRequiredInterval;  // In minutes
    private String deviceTenantMapping;
    private String eventTypeLocation;
    private String eventDescLocation;
    private String eventTypeTeltonika;
    private String eventDescTeltonika;
    private String supportedOperations;
    private int bigDecimalFactor;

    // Static fields for environment variables
    public static final String C8Y_BOOTSTRAP_TENANT = System.getenv("C8Y_BOOTSTRAP_TENANT");
    public static String C8Y_DEFAULT_TENANT = System.getenv("C8Y_DEFAULT_TENANT");
}
