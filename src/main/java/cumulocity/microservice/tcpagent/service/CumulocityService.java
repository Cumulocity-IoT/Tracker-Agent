package cumulocity.microservice.tcpagent.service;

import java.math.BigDecimal;
import java.util.List;
import c8y.IsDevice;
import c8y.Position;
import c8y.RequiredAvailability;

import com.cumulocity.microservice.subscription.model.MicroserviceSubscriptionAddedEvent;
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
import org.springframework.context.event.EventListener;
import org.springframework.integration.ip.tcp.connection.TcpConnection;
import org.springframework.stereotype.Service;
import com.cumulocity.rest.representation.inventory.ManagedObjectRepresentation;
import com.cumulocity.sdk.client.inventory.InventoryApi;
import com.cumulocity.sdk.client.option.TenantOptionApi;

/**
 * This is an example service. This should be removed for your real project!
 * 
 * @author APES
 *
 */
@Slf4j
@Service
@AllArgsConstructor
public class CumulocityService {

    private final static String ID_TYPE = "c8y_IMEI";
    private final static String MO_TYPE = "c8y_Tracker";
    private final static String TCP_AGENT_DEVICE_TENANT_MAPPING = "TCP_AGENT_DEVICE_TENANT_MAPPING";
    private final static String TCP_AGENT_CATEGORY = "TCP_AGENT";
    private final static String TCP_AGENT_KEY = "DEFAULT_TENANT";
    private static final String EVENT_TYPE_LOCATION = "c8y_LocationUpdate";
    private static final String EVENT_DESC_LOCATION = "Location Update";
    private static final String EVENT_TYPE_TELTONIKA = "Teltonika_Events";
    private static final String EVENT_DESC_TELTONIKA = "Teltonika Events";
    private final static String C8Y_BOOTSTRAP_TENANT = System.getenv("C8Y_BOOTSTRAP_TENANT");
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

    @EventListener
    public void onSubscriptionAdded(MicroserviceSubscriptionAddedEvent event) {
        log.info("Subscription added for Tenant ID: <{}> ", event.getCredentials().getTenant());
    }

    public void createDeviceIfNotExist(String imei, String connectionID) {
        try {
            // Check and set device-tenant mapping
            String tenant = GlobalConnectionStore.getImeiToTenant().computeIfAbsent(imei, key -> {
                saveTenantOption(TCP_AGENT_DEVICE_TENANT_MAPPING, imei, C8Y_DEFAULT_TENANT);
                log.info("New Device Tenant mapping added for IMEI: {}", imei);
                return C8Y_DEFAULT_TENANT;
            });
    
            // Update connection ID to IMEI mapping
            GlobalConnectionStore.getConnectionRegistry().computeIfPresent(connectionID, (key, tcpConnectionInfo) -> {
                tcpConnectionInfo.setImei(imei);
                return tcpConnectionInfo;
            });
    
            // Check and set device IMEI to c8y ID mapping
            microserviceSubscriptionsService.runForTenant(tenant, () -> {
                GlobalConnectionStore.getImeiToConn().computeIfAbsent(imei, key -> {
                    try {
                        // Retrieve existing device
                        ExternalIDRepresentation xId = identityApi.getExternalId(new ID(ID_TYPE, imei));
                        log.info("Device already exists IMEI: {}, ID: {}", xId.getExternalId(), xId.getManagedObject().getId().getValue());
                        return new DeviceConnectionInfo(connectionID, imei, xId.getManagedObject().getId().getValue());
                    } catch (SDKException e) {
                        if (e.getHttpStatus() == HttpStatus.SC_NOT_FOUND) {
                            // Create new device if not found
                            String id = createNewDevice(imei, connectionID);
                            return new DeviceConnectionInfo(connectionID, imei, id);
                        } else {
                            throw e;
                        }
                    }
                });
                
                try{
                    // Process the command
                    sendCommand(imei);
                } catch(Exception e){
                    log.error("Failed to process commands from C8Y tenant for IMEI: {}", imei);
                }
            });
    
        } catch (Exception e) {
            log.error("Error occurred while creating device: {}", e.getMessage(), e);
        }
    }
    

    private String createNewDevice(String imei, String connectionID) {
        ManagedObjectRepresentation device = new ManagedObjectRepresentation();
        
        try {
            // Set basic device properties
            device.setName("Tracker-" + imei);
            device.setType(MO_TYPE);
            device.set(new IsDevice());
            
            // Set availability
            RequiredAvailability requiredAvailability = new RequiredAvailability();
            requiredAvailability.setResponseInterval(5);
            device.set(requiredAvailability);
    
            // Set custom properties
            device.setProperty("com_cumulocity_model_Agent", new Object());
            device.setProperty("c8y_SupportedOperations", List.of("c8y_Command"));
    
            // Create the device
            device = inventoryApi.create(device);
    
            // Create external ID for the device
            ExternalIDRepresentation externalIDRepresentation = new ExternalIDRepresentation();
            externalIDRepresentation.setType(ID_TYPE);
            externalIDRepresentation.setExternalId(imei);
            externalIDRepresentation.setManagedObject(ManagedObjects.asManagedObject(device.getId()));
            identityApi.create(externalIDRepresentation);
    
            log.info("Device created for IMEI: {}, Id: {}", imei, device.getId().getValue());
    
            // Return the device ID
            return device.getId().getValue();
    
        } catch (Exception e) {
            log.error("Error creating new device for IMEI {}: {}", imei, e.getMessage(), e);
            return null;
        }
    }
    

    public void createData(Codec8Message msg, String imei) {
        microserviceSubscriptionsService.runForTenant(GlobalConnectionStore.getImeiToTenant().get(imei), () -> {
            //ExternalIDRepresentation xId = identityApi.getExternalId(new ID(ID_TYPE, imei));
            String id = GlobalConnectionStore.getImeiToConn().get(imei).getId();
            for (AvlEntry avlEntry : msg.getAvlData()) {
                if (avlEntry.getSatellites() != 0) {
                    createLocationEvent(avlEntry, id);
                    log.info("Location event created for IMEI {}", imei);
                    updateManagedObjectLocation(avlEntry, id);
                    log.info("Inventory updated for IMEI {}", imei);
                }
                createTeltonikaEvent(avlEntry, id);
                log.info("Teltonika event created for IMEI {}", imei);
            }
        });
    }

    private void createLocationEvent(AvlEntry avlEntry, String id) {
        EventRepresentation event = new EventRepresentation();
        event.setType(EVENT_TYPE_LOCATION);
        event.setText(EVENT_DESC_LOCATION);
        event.setDateTime(new DateTime());

        Position pos = new Position();
        pos.setAlt(new BigDecimal(avlEntry.getAltitude()));
        pos.setLat(new BigDecimal(avlEntry.getLatitude()).divide(new BigDecimal(10000000)));
        pos.setLng(new BigDecimal(avlEntry.getLongitude()).divide(new BigDecimal(10000000)));

        event.set(pos);
        event.setSource(ManagedObjects.asManagedObject(GId.asGId(id)));
        eventApi.create(event);
    }

    private void updateManagedObjectLocation(AvlEntry avlEntry, String id) {
        ManagedObjectRepresentation mo = new ManagedObjectRepresentation();
        mo.setId(GId.asGId(id));
        Position pos = new Position();
        pos.setAlt(new BigDecimal(avlEntry.getAltitude()));
        pos.setLat(new BigDecimal(avlEntry.getLatitude()).divide(new BigDecimal(10000000)));
        pos.setLng(new BigDecimal(avlEntry.getLongitude()).divide(new BigDecimal(10000000)));
        mo.set(pos);
        inventoryApi.update(mo);
    }

    private void createTeltonikaEvent(AvlEntry avlEntry, String id) {
        EventRepresentation trackingEvents = new EventRepresentation();
        trackingEvents.setDateTime(new DateTime());
        trackingEvents.setText(EVENT_DESC_TELTONIKA);
        trackingEvents.setType(EVENT_TYPE_TELTONIKA);
        trackingEvents.setSource(ManagedObjects.asManagedObject(GId.asGId(id)));
        trackingEvents.set(avlEntry, EVENT_TYPE_TELTONIKA);
        eventApi.create(trackingEvents);
    }

    public List<OperationRepresentation> getOperations(String imei, OperationStatus status) {
        OperationFilter operationFilter = new OperationFilter();
        operationFilter.byAgent(GlobalConnectionStore.getImeiToConn().get(imei).getId());
        operationFilter.byStatus(status);
        List<OperationRepresentation> res = deviceControlApi.getOperationsByFilter(operationFilter).get().getOperations();
        log.info("Received {} {} Operations for IMEI: {}",res.size(), status, imei);
        return res;
    }
    
    //@Retryable(value = { RuntimeException.class }, maxAttempts = 3, backoff = @Backoff(delay = 10000))
    public void sendCommand(String imei) {
        log.info("About to process cmd for IMEI: {}", imei);
        DeviceConnectionInfo connectionInfo = getConnectionInfo(imei);
        if (connectionInfo == null) return;

        String connectionId = connectionInfo.getConnectionId();
        TCPConnectionInfo tcpConnectionInfo = getTcpConnection(connectionId);
        if (tcpConnectionInfo == null) return;

        TcpConnection tcpConnection = tcpConnectionInfo.getTcpConnection();
        if (tcpConnection == null || !tcpConnection.isOpen()) {
            log.info("Unable to send command for IMEI {}: TCP connection is closed or unavailable", imei);
            return;
        }

        getOperations(imei, OperationStatus.PENDING).forEach(operation -> {
            try {
				if (tcpConnection == null || !tcpConnection.isOpen()) {
					log.info("Unable to send command for IMEI {}: TCP connection is closed or unavailable", imei);
					return;
				}

                String cmd = processCommand.extractCommandText(operation);
                if (cmd != null && !cmd.isEmpty()) {
                    updateOperationStatus(operation, "EXECUTING");
                    byte[] command = codec12Message.prepareCodec12Message((byte) 0x0C, (short) 1, cmd);
                    processCommand.sendCommandToDevice(imei, command);
                    log.info("Sent command '{}' to connection: {}", codec12Message.bytesToHex(command), connectionId);
                    updateOperationStatus(operation, "SUCCESSFUL");
                } else {
                    log.warn("No valid command text found for operation: {}", operation);
                }
            } catch (Exception e) {
                log.error("Error processing operation for IMEI {}: {}", imei, e.getMessage(), e);
            }
        });
    }

    private DeviceConnectionInfo getConnectionInfo(String imei) {
        DeviceConnectionInfo connectionInfo = GlobalConnectionStore.getImeiToConn().get(imei);
        if (connectionInfo == null) {
            log.warn("No connection found for IMEI: {}", imei);
        }
        return connectionInfo;
    }

    private TCPConnectionInfo getTcpConnection(String connectionId) {
        TCPConnectionInfo tcpConnectionInfo = GlobalConnectionStore.getConnectionRegistry().get(connectionId);
        if (tcpConnectionInfo == null) {
            log.warn("No connection Info found for Connection Id: {}", connectionId);
        }
        return tcpConnectionInfo;
    }

    private void updateOperationStatus(OperationRepresentation operation, String status) {
        operation.setStatus(status);
        operation.setCreationDateTime(null);  // Reset creation date/time if needed
        deviceControlApi.update(operation);
        log.info("Updated operation status of {} to {}", operation.getId().getValue(), status);
    }

    public void loadDefaultTenantOptions() {
		log.info("Load C8Y_BOOTSTRAP_TENANT Id: {}", C8Y_BOOTSTRAP_TENANT);
	
		// Run for the bootstrap tenant to load device-to-tenant mappings
		microserviceSubscriptionsService.runForTenant(C8Y_BOOTSTRAP_TENANT, () -> {
			try {
				// Load all device-to-tenant mappings
				tenantOptionApi.getAllOptionsForCategory(TCP_AGENT_DEVICE_TENANT_MAPPING).forEach(or -> {
					GlobalConnectionStore.getImeiToTenant().put(or.getKey(), or.getValue());
					log.info("Tenant Device Map Entry: Key = {}, Value = {}", or.getKey(), or.getValue());
				});
			} catch (Exception e) {
				log.error("Failed to load device-to-tenant mapping: {}", e.getMessage(), e);
			}
	
			try {
				// Load the default tenant option
				OptionPK op = new OptionPK();
				op.setCategory(TCP_AGENT_CATEGORY);
				op.setKey(TCP_AGENT_KEY);
				C8Y_DEFAULT_TENANT = tenantOptionApi.getOption(op).getValue();
				log.info("C8y DEFAULT_TENANT: {}", C8Y_DEFAULT_TENANT);
			} catch (SDKException e) {
				if (e.getHttpStatus() == HttpStatus.SC_NOT_FOUND) {
					saveTenantOption(TCP_AGENT_CATEGORY, TCP_AGENT_KEY, C8Y_BOOTSTRAP_TENANT);
					C8Y_DEFAULT_TENANT = C8Y_BOOTSTRAP_TENANT;
					log.info("Default tenant not found. Set C8Y_DEFAULT_TENANT as C8Y_BOOTSTRAP_TENANT: {}", C8Y_BOOTSTRAP_TENANT);
				} else {
					log.error("Failed to save default tenant option mapping: {}", e.getMessage(), e);
					throw e;
				}
			} catch (Exception e) {
				log.error("Unexpected error while loading tenant option: {}", e.getMessage(), e);
			}
		});
	}
	

    public void saveTenantOption(String Category, String key, String value) {
        microserviceSubscriptionsService.runForTenant(C8Y_BOOTSTRAP_TENANT, () -> {
            try {
                OptionRepresentation or = new OptionRepresentation();
                or.setCategory(Category);
                or.setKey(key);
                or.setValue(value);
                tenantOptionApi.save(or);
            } catch (Exception e) {
                log.error("Failed to Save Tenantg Option: {}", e.getMessage(), e);
            }
        });
    }
}

