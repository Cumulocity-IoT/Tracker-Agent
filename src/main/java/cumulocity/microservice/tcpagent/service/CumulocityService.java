package cumulocity.microservice.tcpagent.service;

import java.math.BigDecimal;
import java.util.List;
import c8y.IsDevice;
import c8y.Position;
import c8y.RequiredAvailability;
import com.cumulocity.microservice.subscription.service.MicroserviceSubscriptionsService;
import com.cumulocity.model.ID;
import com.cumulocity.model.idtype.GId;
import com.cumulocity.model.operation.OperationStatus;
import com.cumulocity.model.option.OptionPK;
import com.cumulocity.rest.representation.event.EventRepresentation;
import com.cumulocity.rest.representation.identity.ExternalIDRepresentation;
import com.cumulocity.rest.representation.inventory.ManagedObjects;
import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.cumulocity.rest.representation.tenant.OptionRepresentation;
import com.cumulocity.sdk.client.SDKException;
import com.cumulocity.sdk.client.devicecontrol.DeviceControlApi;
import com.cumulocity.sdk.client.devicecontrol.OperationFilter;
import com.cumulocity.sdk.client.event.EventApi;
import com.cumulocity.sdk.client.identity.IdentityApi;
import cumulocity.microservice.tcpagent.tcp.GlobalConnectionStore;
import cumulocity.microservice.tcpagent.tcp.ProcessCommand;
import cumulocity.microservice.tcpagent.tcp.model.AvlEntry;
import cumulocity.microservice.tcpagent.tcp.model.Codec12Message;
import cumulocity.microservice.tcpagent.tcp.model.Codec8Message;
import cumulocity.microservice.tcpagent.tcp.model.DeviceConnectionInfo;
import cumulocity.microservice.tcpagent.tcp.model.TCPConnectionInfo;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpStatus;
import org.joda.time.DateTime;
import org.springframework.integration.ip.tcp.connection.TcpConnection;
import org.springframework.stereotype.Service;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.option.TenantOptionApi;

@Slf4j
@Service
@AllArgsConstructor
public class CumulocityService {

    private static final String ID_TYPE = "c8y_IMEI";
    private static final String MO_TYPE = "c8y_Tracker";
    private static final String MO_NAME_PREFIX = "Tracker-";
    private static final int C8Y_REQUIRED_INTERVAL = 15;  //minutes
    private static final String TCP_AGENT_DEVICE_TENANT_MAPPING = "TCP_AGENT_DEVICE_TENANT_MAPPING";
    private static final String TCP_AGENT_CATEGORY = "TCP_AGENT";
    private static final String TCP_AGENT_KEY = "DEFAULT_TENANT";
    private static final String EVENT_TYPE_LOCATION = "c8y_LocationUpdate";
    private static final String EVENT_DESC_LOCATION = "Location Update";
    private static final String EVENT_TYPE_TELTONIKA = "Teltonika_Events";
    private static final String EVENT_DESC_TELTONIKA = "Teltonika Events";
    private static String C8Y_BOOTSTRAP_TENANT = System.getenv("C8Y_BOOTSTRAP_TENANT");
    private static String C8Y_DEFAULT_TENANT;

    private InventoryApi inventoryApi;
    private IdentityApi identityApi;
    private EventApi eventApi;
    private MicroserviceSubscriptionsService microserviceSubscriptionsService;
    private DeviceControlApi deviceControlApi;
    private TenantOptionApi tenantOptionApi;
    private ProcessCommand processCommand;
    private final Codec12Message codec12Message;

    @PostConstruct
    public void init() {
        loadDefaultTenantOptions();
    }

    public void createDeviceIfNotExist(String imei, String connectionID) {
        try {
            String tenant = GlobalConnectionStore.getImeiToTenant()
                .computeIfAbsent(imei, key -> {
                    saveTenantOption(TCP_AGENT_DEVICE_TENANT_MAPPING, imei, C8Y_DEFAULT_TENANT);
                    return C8Y_DEFAULT_TENANT;
                });

            updateConnectionInfo(connectionID, imei);

            microserviceSubscriptionsService.runForTenant(tenant, () -> {
                processDeviceCreation(imei, connectionID);
            });
        } catch (Exception e) {
            log.error("Error occurred while creating device: {}", e.getMessage(), e);
        }
    }

    private void updateConnectionInfo(String connectionID, String imei) {
        GlobalConnectionStore.getConnectionRegistry().computeIfPresent(connectionID, (key, tcpConnectionInfo) -> {
            tcpConnectionInfo.setImei(imei);
            return tcpConnectionInfo;
        });
    }

    private void processDeviceCreation(String imei, String connectionID) {
        GlobalConnectionStore.getImeiToConn().computeIfAbsent(imei, key -> {
            try {
                ExternalIDRepresentation xId = identityApi.getExternalId(new ID(ID_TYPE, imei));
                return new DeviceConnectionInfo(connectionID, imei, xId.getManagedObject().getId().getValue());
            } catch (SDKException e) {
                if (e.getHttpStatus() == HttpStatus.SC_NOT_FOUND) {
                    return new DeviceConnectionInfo(connectionID, imei, createNewDevice(imei, connectionID));
                } else {
                    throw e;
                }
            }
        });

        try {
            sendCommand(imei);
        } catch (Exception e) {
            log.error("Failed to process commands for IMEI: {}", imei, e);
        }
    }

    private String createNewDevice(String imei, String connectionID) {
        ManagedObjectRepresentation device = new ManagedObjectRepresentation();
        device.setName(MO_NAME_PREFIX + imei);
        device.setType(MO_TYPE);
        device.set(new IsDevice());

        RequiredAvailability requiredAvailability = new RequiredAvailability();
        requiredAvailability.setResponseInterval(C8Y_REQUIRED_INTERVAL);
        device.set(requiredAvailability);

        device.setProperty("com_cumulocity_model_Agent", new Object());
        device.setProperty("c8y_SupportedOperations", List.of("c8y_Command"));

        try {
            device = inventoryApi.create(device);

            ExternalIDRepresentation externalIDRepresentation = new ExternalIDRepresentation();
            externalIDRepresentation.setType(ID_TYPE);
            externalIDRepresentation.setExternalId(imei);
            externalIDRepresentation.setManagedObject(ManagedObjects.asManagedObject(device.getId()));
            identityApi.create(externalIDRepresentation);

            log.info("Device created for IMEI: {}, Id: {}", imei, device.getId().getValue());
            return device.getId().getValue();
        } catch (Exception e) {
            log.error("Error creating new device for IMEI {}: {}", imei, e.getMessage(), e);
            return null;
        }
    }

    public void createData(Codec8Message msg, String imei) {
        String tenant = GlobalConnectionStore.getImeiToTenant().get(imei);
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
        EventRepresentation event = createEvent(EVENT_TYPE_LOCATION, EVENT_DESC_LOCATION, id, avlEntry);
        eventApi.create(event);
    }

    private void createTeltonikaEvent(AvlEntry avlEntry, String id) {
        EventRepresentation event = createEvent(EVENT_TYPE_TELTONIKA, EVENT_DESC_TELTONIKA, id, avlEntry);
        eventApi.create(event);
    }

    private EventRepresentation createEvent(String eventType, String eventDesc, String id, AvlEntry avlEntry) {
        EventRepresentation event = new EventRepresentation();
        event.setType(eventType);
        event.setText(eventDesc);
        event.setDateTime(new DateTime());
        event.set(buildPosition(avlEntry));

        event.setSource(ManagedObjects.asManagedObject(GId.asGId(id)));
        event.set(avlEntry, eventType);
        return event;
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
        position.setLat(BigDecimal.valueOf(avlEntry.getLatitude()).divide(BigDecimal.valueOf(10000000)));
        position.setLng(BigDecimal.valueOf(avlEntry.getLongitude()).divide(BigDecimal.valueOf(10000000)));
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

        getOperations(imei, OperationStatus.PENDING).forEach(operation -> processCommandForOperation(imei, tcpConnection, operation));
    }

    private void processCommandForOperation(String imei, TcpConnection tcpConnection, OperationRepresentation operation) {
        try {
            String cmd = processCommand.extractCommandText(operation);
            if (cmd != null && !cmd.isEmpty()) {
                updateOperationStatus(operation, "EXECUTING");
                byte[] command = codec12Message.prepareCodec12Message((byte) 0x0C, (short) 1, cmd);
                processCommand.sendCommandToDevice(imei, command);
                log.info("Sent command '{}' to connection: {}", codec12Message.bytesToHex(command), imei);
                updateOperationStatus(operation, "SUCCESSFUL");
            } else {
                log.warn("No valid command text found for operation: {}", operation);
            }
        } catch (Exception e) {
            log.error("Error processing operation for IMEI {}: {}", imei, e.getMessage(), e);
        }
    }

    private void updateOperationStatus(OperationRepresentation operation, String status) {
        operation.setStatus(status);
        deviceControlApi.update(operation);
        log.info("Updated operation status of {} to {}", operation.getId().getValue(), status);
    }

    public void loadDefaultTenantOptions() {
        log.info("Loading C8Y_BOOTSTRAP_TENANT Id: {}", C8Y_BOOTSTRAP_TENANT);
        microserviceSubscriptionsService.runForTenant(C8Y_BOOTSTRAP_TENANT, () -> {
            loadDeviceToTenantMappings();
            loadDefaultTenantOption();
        });
    }

    private void loadDeviceToTenantMappings() {
        try {
            tenantOptionApi.getAllOptionsForCategory(TCP_AGENT_DEVICE_TENANT_MAPPING).forEach(or -> {
                GlobalConnectionStore.getImeiToTenant().put(or.getKey(), or.getValue());
                log.info("Tenant Device Map Entry: Key = {}, Value = {}", or.getKey(), or.getValue());
            });
        } catch (Exception e) {
            log.error("Failed to load device-to-tenant mapping: {}", e.getMessage(), e);
        }
    }

    private void loadDefaultTenantOption() {
        try {
            OptionPK op = new OptionPK();
            op.setCategory(TCP_AGENT_CATEGORY);
            op.setKey(TCP_AGENT_KEY);
            C8Y_DEFAULT_TENANT = tenantOptionApi.getOption(op).getValue();
            log.info("C8Y_DEFAULT_TENANT: {}", C8Y_DEFAULT_TENANT);
        } catch (SDKException e) {
            handleDefaultTenantOptionError(e);
        } catch (Exception e) {
            log.error("Unexpected error while loading tenant option: {}", e.getMessage(), e);
        }
    }

    private void handleDefaultTenantOptionError(SDKException e) {
        if (e.getHttpStatus() == HttpStatus.SC_NOT_FOUND) {
            saveTenantOption(TCP_AGENT_CATEGORY, TCP_AGENT_KEY, C8Y_BOOTSTRAP_TENANT);
            C8Y_DEFAULT_TENANT = C8Y_BOOTSTRAP_TENANT;
            log.info("Default tenant not found. Set C8Y_DEFAULT_TENANT as C8Y_BOOTSTRAP_TENANT: {}", C8Y_BOOTSTRAP_TENANT);
        } else {
            log.error("Failed to save default tenant option mapping: {}", e.getMessage(), e);
            throw e;
        }
    }

    public void saveTenantOption(String category, String key, String value) {
        microserviceSubscriptionsService.runForTenant(C8Y_BOOTSTRAP_TENANT, () -> {
            try {
                OptionRepresentation or = new OptionRepresentation();
                or.setCategory(category);
                or.setKey(key);
                or.setValue(value);
                tenantOptionApi.save(or);
            } catch (Exception e) {
                log.error("Failed to save tenant option: {}", e.getMessage(), e);
            }
        });
    }
}
