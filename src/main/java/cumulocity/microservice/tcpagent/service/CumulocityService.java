package cumulocity.microservice.tcpagent.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import c8y.Position;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjects;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.devicecontrol.OperationFilter;
import com.cumulocity.sdk.client.event.EventApi;
import cumulocity.microservice.tcpagent.tcp.GlobalConnectionStore;
import cumulocity.microservice.tcpagent.tcp.ProcessCommand;
import cumulocity.microservice.tcpagent.tcp.model.AvlEntry;
import cumulocity.microservice.tcpagent.tcp.model.Codec12Message;
import cumulocity.microservice.tcpagent.tcp.model.Codec8Message;
import cumulocity.microservice.tcpagent.tcp.model.DeviceConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.model.TCPConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import cumulocity.microservice.tcpagent.tcp.util.CodecConfig;
import cumulocity.microservice.tcpagent.tcp.util.ConfigProperties;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.springframework.context.event.EventListener;
import org.springframework.integration.ip.tcp.connection.TcpConnection;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.option.TenantOptionApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Service
@AllArgsConstructor
public class CumulocityService {

    private InventoryApi inventoryApi;
    private EventApi eventApi;
    private MicroserviceSubscriptionsService microserviceSubscriptionsService;
    private DeviceControlApi deviceControlApi;
    private ProcessCommand processCommand;
    private final ConfigProperties config;
    private final CodecConfig codecConfig;
    private final ObjectMapper mapper;
    

    @PostConstruct
    public void init() {
       setDefaultTenant();
       loadSubscribedTenants();
       loadDefaultTenantOptions();
    }
       
    @EventListener
    public void onSubscriptionAdded(MicroserviceSubscriptionAddedEvent event) throws Exception {
        GlobalConnectionStore.getTenants().add(event.getCredentials().getTenant());
        log.info("Subscription added for Tenant ID: <{}> ", event.getCredentials().getTenant()); 
    }

    private void updateConnectionInfo(String connectionID, String imei) {
        GlobalConnectionStore.getConnectionRegistry().computeIfPresent(connectionID, (key, tcpConnectionInfo) -> {
            tcpConnectionInfo.setImei(imei);
            log.info("update imei to connection Registry Map: {}", imei);
            return tcpConnectionInfo;
        });
    }

    public void updateConnectionAndProcessOperations(String imei, String connectionID) {
        try {
            // Update connection info (assumed required regardless of device existence)
            updateConnectionInfo(connectionID, imei);
    
            // Fetch existing connection
            DeviceConnectionInfo existingConnection = GlobalConnectionStore.getImeiToConn().get(imei);
    
            // Early exit if the device is not registered
            if (existingConnection == null) {
                log.warn("Tracker device {} not found. Please register it in the tenant before attempting to connect.", imei);
                return;
            }
    
            // Update connection ID
            existingConnection.setConnectionId(connectionID);
            log.info("Updated connection ID for device {}", imei);
    
            // Run command in the device's tenant context
            microserviceSubscriptionsService.runForTenant(existingConnection.getTenantId(), () -> sendCommand(imei));
    
        } catch (Exception e) {
            log.error("Failed to process commands for IMEI: {}. Error: {}", imei, e.getMessage(), e);
        }
    }
    
    
    public void createData(Codec8Message msg, String imei) {
        String tenant = GlobalConnectionStore.getImeiToConn().get(imei).getTenantId();
        String id = GlobalConnectionStore.getImeiToConn().get(imei).getId();
    
        // Run processing for the tenant and iterate over AVL entries.
        microserviceSubscriptionsService.runForTenant(tenant, () -> {
            for (AvlEntry avlEntry : msg.getAvlData()) {
                processAvlEntry(avlEntry, id, imei);
            }
        });
    }

    private void processAvlEntry(AvlEntry avlEntry, String id, String imei) {
        if (avlEntry.getSatellites() != 0) {
            createLocationEvent(avlEntry, id);
            log.info("created location update event for IMEI: {}", imei);
            updateManagedObjectLocation(avlEntry, id);
            log.info("location updated for tracker IMEI: {}", imei);
        }
        createTeltonikaEvent(avlEntry, id);
        log.info("created teltonka event for IMEI: {}", imei);
    }

    private void createLocationEvent(AvlEntry avlEntry, String id) {
        EventRepresentation event = new EventRepresentation();
        event.setType(config.getEventTypeLocation());
        event.setText(config.getEventDescLocation());
        event.setDateTime(new DateTime());
        event.set(buildPosition(avlEntry));

        event.setSource(ManagedObjects.asManagedObject(GId.asGId(id)));
        eventApi.create(event);
    }

    private void createTeltonikaEvent(AvlEntry avlEntry, String id) {
        EventRepresentation event = new EventRepresentation();
        event.setType(config.getEventTypeTeltonika());
        event.setText(config.getEventDescTeltonika());
        event.setDateTime(new DateTime());

        event.setSource(ManagedObjects.asManagedObject(GId.asGId(id)));
        event.set(avlEntry, config.getEventTypeTeltonika());
        eventApi.create(event);
    }

    private void updateManagedObjectLocation(AvlEntry avlEntry, String id) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(GId.asGId(id));
        mo.set(buildPosition(avlEntry));
        inventoryApi.update(mo);
    }

    private Position buildPosition(AvlEntry avlEntry) {
        Position position = new Position();
        position.setAlt(BigDecimal.valueOf(avlEntry.getAltitude()));
        position.setLat(BigDecimal.valueOf(avlEntry.getLatitude()).divide(BigDecimal.valueOf(config.getBigDecimalFactor())));
        position.setLng(BigDecimal.valueOf(avlEntry.getLongitude()).divide(BigDecimal.valueOf(config.getBigDecimalFactor())));
        return position;
    }

    public List<OperationRepresentation> getOperations(String imei, OperationStatus status) {
        OperationFilter operationFilter = new OperationFilter()
            .byAgent(GlobalConnectionStore.getImeiToConn().get(imei).getId())
            .byStatus(status);
        List<OperationRepresentation> operations = deviceControlApi.getOperationsByFilter(operationFilter).get().getOperations();
        log.info("Received {} {} Operations for IMEI: {}", operations.size(), status, imei);
        return operations;
    }

    public void sendCommand(String imei) {
        log.info("About to process command for IMEI: {}", imei);

        DeviceConnectionInfo connectionInfo = GlobalConnectionStore.getImeiToConn().get(imei);
        if (connectionInfo == null) return;

        TCPConnectionInfo tcpConnectionInfo = GlobalConnectionStore.getConnectionRegistry().get(connectionInfo.getConnectionId());
        if (tcpConnectionInfo == null) return;

        TcpConnection tcpConnection = tcpConnectionInfo.getTcpConnection();
        if (tcpConnection == null || !tcpConnection.isOpen()) {
            log.info("Unable to send command for IMEI {}: TCP connection is closed or unavailable", imei);
            return;
        }
        getOperations(imei, OperationStatus.EXECUTING).forEach(operation -> processCommandForOperation(imei, tcpConnection, operation));
        getOperations(imei, OperationStatus.PENDING).forEach(operation -> processCommandForOperation(imei, tcpConnection, operation));
    }

    private void processCommandForOperation(String imei, TcpConnection tcpConnection, OperationRepresentation operation) {
        try {
            String cmd = processCommand.extractCommandText(operation);
            if (cmd != null && !cmd.isEmpty()) {
                if(operation.getStatus().equals(OperationStatus.PENDING.name()))
                    updateOperationStatus(operation, OperationStatus.EXECUTING.name());
                Codec12Message codec12Message = new Codec12Message(cmd, codecConfig);
                processCommand.sendCommandToDevice(imei, codec12Message.command);
                log.info("Sent command '{}' to connection: {}", BytesUtil.bytesToHex(codec12Message.command), imei);
                updateOperationStatus(operation, OperationStatus.SUCCESSFUL.name());
            } else {
                log.warn("No valid command text found for operation: {}", operation);
            }
        } catch (Exception e) {
            operation.setFailureReason(e.getMessage());
            updateOperationStatus(operation, OperationStatus.FAILED.name());
            log.error("Error processing operation for IMEI {}: {}", imei, e.getMessage(), e);
        }
    }

    private void updateOperationStatus(OperationRepresentation operation, String status) {
        operation.setStatus(status);
        deviceControlApi.update(operation);
        log.info("Updated operation status of {} to {}", operation.getId().getValue(), status);
    }

    //Schedule this service once in a day
    @Scheduled(cron = "#{@getCronExpression}")
    public void loadDefaultTenantOptions() {
        for(String tenant: GlobalConnectionStore.getTenants()){     
        log.info("Loading Device for tenant Id: {}", tenant);
            microserviceSubscriptionsService.runForTenant(tenant, () -> {
                loadDeviceToTenantMappings(tenant);
            });
        }
    }

    private void loadDeviceToTenantMappings(String tenantId) {
        try {
            
            inventoryApi.getManagedObjectsByFilter(new InventoryFilter().byType(config.getMoType()))
            .get().allPages().forEach(mo -> {
                try {
                    // Get the 'c8y_Mobile' field directly as a JsonNode
                    JsonNode mobileNode = mapper.readTree(mapper.writeValueAsString(mo.get("c8y_Mobile")));

                    if (mobileNode != null) {
                        // Extract the IMEI value from the 'c8y_Mobile' JSON node
                        String imei = mobileNode.path("imei").asText(null);

                        // Only process if IMEI is available
                        if (imei != null && !imei.isEmpty()) {
                            // Add or update the IMEI-to-connection mapping using computeIfAbsent
                            GlobalConnectionStore.getImeiToConn().computeIfAbsent(imei, key -> {
                                log.info("Tenant Device Map Entry: Key = {}, Value = {}", imei, mo.getId().getValue());
                                return new DeviceConnectionInfo(null, imei, mo.getId().getValue(), tenantId);
                            });
                        } else {
                            log.warn("IMEI is missing or empty for Managed Object ID {}.", mo.getId().getValue());
                        }
                    } else {
                        log.warn("c8y_Mobile field is missing for Managed Object ID {}.", mo.getId().getValue());
                    }
                } catch (Exception e) {
                    log.warn("Failed to process Managed Object ID {}: {}", mo.getId().getValue(), e.getMessage());
                }
            });
            } catch (Exception e) {
            log.error("Failed to load device-to-tenant mapping: {}", e.getMessage(), e);
            }
    }

    private void setDefaultTenant() {
        try {
            if (ConfigProperties.C8Y_DEFAULT_TENANT == null) {
                ConfigProperties.C8Y_DEFAULT_TENANT = ConfigProperties.C8Y_BOOTSTRAP_TENANT;
                log.info("Default tenant not found. Setting C8Y_BOOTSTRAP_TENANT as C8Y_DEFAULT_TENANT: {}", ConfigProperties.C8Y_DEFAULT_TENANT);
            } else {
                log.info("C8Y_DEFAULT_TENANT already set: {}", ConfigProperties.C8Y_DEFAULT_TENANT);
            }
        } catch (Exception e) {
            log.error("Error while setting the default tenant: {}", e.getMessage(), e);
        }
    }

    private void loadSubscribedTenants() {
        String tenantsProperty = System.getenv("C8Y_SUBSCRIBED_TENANTS");
        log.info("subs: {}", tenantsProperty);
        if (tenantsProperty != null && !tenantsProperty.isEmpty()) {
            List<String> tenants = Arrays.stream(tenantsProperty.split(","))
                                         .map(String::trim)
                                         .filter(s -> !s.isEmpty())
                                         .collect(Collectors.toList());
            GlobalConnectionStore.getTenants().addAll(tenants);
            log.info("C8Y_SUBSCRIBED_TENANTS {}",tenants);
        }
       }
}
