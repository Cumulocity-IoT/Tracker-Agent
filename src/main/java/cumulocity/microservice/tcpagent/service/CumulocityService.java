package cumulocity.microservice.tcpagent.service;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.stream.StreamSupport;

import c8y.Position;
import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjects;
import com.cumulocity.rest.representation.measurement.MeasurementRepresentation;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.devicecontrol.OperationFilter;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.IdentityApi;

import cumulocity.microservice.tcpagent.tcp.GlobalConnectionStore;
import cumulocity.microservice.tcpagent.tcp.ProcessCommand;
import cumulocity.microservice.tcpagent.tcp.model.AvlEntry;
import cumulocity.microservice.tcpagent.tcp.model.Codec12Message;
import cumulocity.microservice.tcpagent.tcp.model.TeltonikaCodecMessage;
import cumulocity.microservice.tcpagent.tcp.model.DeviceConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.model.TCPConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.model.MeasurementSeries;
import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import cumulocity.microservice.tcpagent.tcp.util.CodecConfig;
import cumulocity.microservice.tcpagent.tcp.util.ConfigProperties;
import cumulocity.microservice.tcpagent.tcp.util.VehicleConfig;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.http.HttpStatus;
import org.joda.time.DateTime;
import org.springframework.context.event.EventListener;
import org.springframework.integration.ip.tcp.connection.TcpConnection;
import org.springframework.stereotype.Service;

import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.inventory.InventoryFilter;
import com.cumulocity.sdk.client.measurement.MeasurementApi;

@Slf4j
@Service
@AllArgsConstructor
public class CumulocityService {

    private InventoryApi inventoryApi;
    private IdentityApi identityApi;
    private EventApi eventApi;
    private MeasurementApi measurementApi;
    private MicroserviceSubscriptionsService microserviceSubscriptionsService;
    private DeviceControlApi deviceControlApi;
    private ProcessCommand processCommand;
    private final ConfigProperties config;
    private final CodecConfig codecConfig;
    private final VehicleConfig vehicleConfig;
    

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
        updateConnectionInfo(connectionID, imei);
        Map<String, DeviceConnectionInfo> imeiToConn = GlobalConnectionStore.getImeiToConn();

        DeviceConnectionInfo existingConnection = imeiToConn.get(imei);

        if (existingConnection == null) {
            existingConnection = findOrFetchDeviceConnectionInfo(imei, connectionID);
            if(existingConnection != null)
                imeiToConn.putIfAbsent(imei, existingConnection);
            else
                log.warn("Tracker device {} not found. Please register it in the tenant before attempting to connect.", imei);
                return;
        }

        existingConnection.setConnectionId(connectionID);
        log.info("Updated connection ID for device {}", imei);

        microserviceSubscriptionsService.runForTenant(existingConnection.getTenantId(), () -> sendCommand(imei));

    } catch (Exception e) {
        log.error("Failed to process commands for IMEI: {}. Error: {}", imei, e.getMessage(), e);
    }
}
    
    
    public void createData(TeltonikaCodecMessage msg, String imei) {
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
        log.info("Created Tracker Event for IMEI: {}", imei);
        createTeltonikaMeasurement(avlEntry, id);
        log.info("Created Tracker Measurement for IMEI: {}", imei);
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

    private void createTeltonikaMeasurement(AvlEntry avlEntry, String id) {
        try {
            MeasurementRepresentation measurement = new MeasurementRepresentation();
            measurement.setType(config.getMeasurementType());
            measurement.setSource(ManagedObjects.asManagedObject(GId.asGId(id)));
            measurement.setDateTime(new DateTime());
            measurement.setProperty(config.getMeasurementType(), prepareMeasurements(avlEntry.getEvents()));
            measurementApi.create(measurement);
        } catch (Exception e) {
            log.error("Error creating Teltonika measurement for ID {}: {}", id, e.getMessage(), e);
        }
    }
    
    private Map<String, MeasurementSeries> prepareMeasurements(Map<String, String> avlEntry) {
        Map<String, MeasurementSeries> series = new HashMap<>();
        
        try {
            for (Map.Entry<String, String> entry : avlEntry.entrySet()) {
                String keyHex = entry.getKey();
                String valueHex = entry.getValue();
    
                // Convert hex values to integers
                int keyInt = BytesUtil.hextoInt(keyHex);
                double valueInt = BytesUtil.hextoDouble(valueHex);
    
                // Retrieve parameter details
                String keyStr = String.valueOf(keyInt);
                // Add to measurement map
                series.put(vehicleConfig.getParameters().getOrDefault(keyStr+"_name", keyStr),
                 new MeasurementSeries(valueInt, vehicleConfig.getParameters().getOrDefault(keyStr+"_unit", "")));
            }
    
            log.info("Prepared measurements: {}", series);
    
        } catch (Exception e) {
            log.error("Error preparing measurements for AVL entry: {}", avlEntry, e);
        }
    
        return series;
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
                .get().allPages().forEach(mo -> getImeiAndUpdateMap(mo, tenantId));
        } catch (Exception e) {
            log.error("Failed to load device-to-tenant mapping: {}", e.getMessage(), e);
        }
    }


    private DeviceConnectionInfo findOrFetchDeviceConnectionInfo(String imei, String connectionID) {
        for (String tenant : GlobalConnectionStore.getTenants()) {
            log.info("Searching for device {} in tenant {}", imei, tenant);

            DeviceConnectionInfo newConnection = microserviceSubscriptionsService.callForTenant(tenant, () -> {
                // Avoid redundant lookups and fetch only if absent
                return fetchDeviceConnectionInfo(imei, connectionID, tenant);
            });

            if (newConnection != null) {
                return newConnection; // Device found, return immediately
            }
        }

        log.warn("Device {} not found in any tenant.", imei);
        return null; // Return null if the device is not found
    }

    private DeviceConnectionInfo fetchDeviceConnectionInfo(String imei, String connectionID, String tenantId) {
        try {
            ExternalIDRepresentation xId = identityApi.getExternalId(new ID(config.getIdType(), imei));
            
            String managedObjectId = xId.getManagedObject().getId().getValue();
            log.info("Successfully fetched device {} in tenant {} with managed object ID: {}", imei, tenantId, managedObjectId);

            return new DeviceConnectionInfo(connectionID, imei, managedObjectId, tenantId);

        } catch (SDKException e) {
            if (e.getHttpStatus() == HttpStatus.SC_NOT_FOUND) {
                log.warn("Device {} not found in tenant {}.", imei, tenantId);
                return null; // Return null instead of throwing if not found
            }

            log.error("Error fetching device {} in tenant {}: {}", imei, tenantId, e.getMessage());
            throw e; // Rethrow only unexpected errors
        }
    }



    private void getImeiAndUpdateMap(ManagedObjectRepresentation mo, String tenantId) {
        try {
            Iterable<ExternalIDRepresentation> externalIds = identityApi.getExternalIdsOfGlobalId(mo.getId()).get(0);

            Optional<ExternalIDRepresentation> imeiOptional = StreamSupport.stream(externalIds.spliterator(), false)
                .filter(ex -> "c8y_IMEI".equals(ex.getType()))
                .findFirst();

            imeiOptional.ifPresentOrElse(
                ex -> mapImeiToConnection(ex.getExternalId(), mo, tenantId),
                () -> log.warn("IMEI is missing for Managed Object ID {}.", mo.getId().getValue())
            );
        } catch (Exception e) {
            log.warn("Failed to process Managed Object ID {}: {}", mo.getId().getValue(), e.getMessage());
        }
    }

    
    private void mapImeiToConnection(String imei, ManagedObjectRepresentation mo, String tenantId) {
        GlobalConnectionStore.getImeiToConn().computeIfAbsent(imei, key -> {
            log.info("Tenant Device Map Entry: IMEI = {}, ManagedObject ID = {}", imei, mo.getId().getValue());
            return new DeviceConnectionInfo(null, imei, mo.getId().getValue(), tenantId);
        });
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
        if (tenantsProperty != null && !tenantsProperty.isEmpty()) {
            List<String> tenants = Arrays.stream(tenantsProperty.split(","))
                                         .map(String::trim)
                                         .filter(s -> !s.isEmpty())
                                         .collect(Collectors.toList());
            GlobalConnectionStore.getTenants().addAll(tenants);
            log.info("C8Y_SUBSCRIBED_TENANTS {}",tenants);
        }else
            log.info("Subscribed Tenants not injected"); 
       }
}
