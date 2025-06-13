# Tracker TCP Agent Microservice

## 📌 Description

The **Tracker TCP Agent microservice** operates independently from the Cumulocity platform to overcome security restrictions on custom TCP ports. This **stateless microservice** is designed to run in a Kubernetes environment using a **load balancer** to handle concurrent device connections.

## 🔗 Connectivity Protocol

- **Device → TCP Agent:** TCP
- **TCP Agent → Cumulocity:** HTTPS (bidirectional)

## ⚙️ Functionality Overview

- Tracker devices connect via TCP and send IMEI in CODEC 8 / 8E format.
- The agent checks for pending commands and sends them in Codec12 format.
- Command exchange occurs only after device identification (IMEI).
- Supports **bidirectional** communication with Cumulocity.

![TCP Agent Architecture](image.png)

---

## 📡 Supported Devices

- **Vendor:** Teltonika  
- **Message Protocols:**  
  - CODEC 8  
  - CODEC 8E  
  - CODEC 12  

---

## 🔧 How It Works


1. Create the "tcp-agent" microservice in you tenant and subscribe it
   C8y API endpoint - [Create Application API](https://cumulocity.com/api/core/#operation/postApplicationCollectionResource)
   ```json
   {
	"apiVersion": "2",
	"version": "0.0.1",
    "availability": "MARKET",
    "type": "MICROSERVICE",
    "name": "tcp-agent",
    "key": "tcp-agent-key",
	"provider": {
		"name": "Cumulocity GmbH"
	},
	"isolation": "MULTI_TENANT",
	"resources": {
        "cpu": "1",
        "memory": "512M"
    },
	"requiredRoles": [
		"ROLE_INVENTORY_READ",
		"ROLE_INVENTORY_CREATE",
		"ROLE_INVENTORY_ADMIN",
		"ROLE_IDENTITY_READ",
		"ROLE_IDENTITY_ADMIN",
		"ROLE_AUDIT_READ",
		"ROLE_AUDIT_ADMIN",
		"ROLE_MEASUREMENT_READ",
		"ROLE_MEASUREMENT_ADMIN",
		"ROLE_EVENT_READ",
		"ROLE_EVENT_ADMIN",
		"ROLE_DEVICE_CONTROL_READ",
		"ROLE_DEVICE_CONTROL_ADMIN",
		"ROLE_APPLICATION_MANAGEMENT_READ",
		"ROLE_TENANT_MANAGEMENT_READ",
		"ROLE_OPTION_MANAGEMENT_READ",
		"ROLE_OPTION_MANAGEMENT_ADMIN"

	],
	"roles": [
	],
	"livenessProbe": {
		"httpGet": {
			"path": "/health",
			"port": 80
		},
		"initialDelaySeconds": 30,
		"periodSeconds": 10
	},
	"readinessProbe": {
		"httpGet": {
			"path": "/health",
			"port": 80
		},
		"initialDelaySeconds": 30,
		"periodSeconds": 10
	}
    } 
   ```
2. **Bootstrap Credentials**
   - Set in a `deployment.yml` file.
   
### Internal State (In-Memory Maps)
3. **connectionRegistry**
   ```json
   {
     "connectionId": {
       "imei": "",
       "TCPConnection": ""
     }
   }

4. **Build & Deploy**
   - This Microservice must be build as Docker imageand could be deployed independently as docker container or any kubernetes cluster 
   - Java17, Maven 3.6 and docker latest must be installed on the machin to build this microservice docker image
   - Using below maven command this could be built
     ```cmd
      cd {project root directory}
      ```
      Build the microservice
      ```cmd
      mvn clean install 
      ```
      Change Directory
      ```cmd
      cd target/docker-work
      ```
      Build the microservice & upload in local docker repo
      ```cmd
      docker build -t tcp-agent:1.0 .
      ```
      deploy the microservice
      ```cmd
      kubectl apply -f deployment.yml 
      ```
   - Prerequisite
     - Java 17 or later
     - Cumulocity Java SDK (1020.155.0 or later)
     - A cumulocity microservice must be created and subscribed on the enterprise tenant as well as other subtenants where this Tracker Agent will be interacting. This will be a dummy microservice no zip has to be uploaded it will be used by the Tracker agent to take advantage of C8Y Java SDK multi-tenancy features.
5. **connectionRegistry**
   - Stores TCP connection IDs as keys and a combination of IMEI and TCP connection objects as values.
   - Populated when a tracker device connects to the agent.
   ```json
   {
       "connectionId": {
           "imei": "",
           "TCPConnection": ""
       }
   }
   ```

6. **imeiToConn**
   - Stores IMEI numbers as keys and DeviceConnectionInfo objects (connection ID, Cumulocity device ID, IMEI and tenant Id) as values.
   - Populated when a device connects to the agent and used for processing commands and identifying connections for sending commands back to devices.
   ```json
   {
       "imei": {
           "id": "",
           "imei": "",
           "connectionId": "",
           "tenantId":""
       }
   }
   ```

7. **Tenants**
   - Maintains the list of tenants subscibed to this microservice.
   ```json
   [
    "t11974744",
    "t11974745"
   ]
   ```

## Device Registration
- **Default Tenant:**
  - Customers must populate the default tenant information in the Cumulocity bootstrap tenant option to enable the agent.
  - If unspecified, the bootstrap tenant will act as the default.
  - Example tenant option JSON:
    ```json
    {
        "DEFAULT_TENANT": "t11974744"
    }
    ```
- On receiving an IMEI number, the agent:
  1. Lookup the global registry to check if device exist
  2. If not found, tcp-agent will lookup all the subscribed tenants and check for device and update the global registery.
  3. Still not found then log an error device not registered. please register the device in a tenant before sending data
  4. continue thw processing

## Data Processing
- On receiving Teltonika CODEC 8 or CODEC 8E data:
  1. The agent deserializes the buffered data and converts it into Cumulocity events.
  2. For each Codec8 entry, a Teltonika Location Update Event is created in Cumulocity.
  3. The tracker’s current location is updated in the inventory object.
  4. Convert all the AVl properties in the measurement value and insert as measurement as well
- Lcation Update Event JSON:
    ```json
    {
            "id": "147185450",
            "type": "c8y_LocationUpdate",
            "text": "Location Update",
            "source": {
                "id": "95147185373",
                "name": "Tracker 356307042441014"
            },
            "time": "2025-01-21T17:20:19.176+05:30",
            "creationTime": "2025-01-21T11:50:18.171Z",
            "lastUpdated": "2025-01-21T11:50:18.171Z",
            "c8y_Position": {
                "lng": 46.7399983,
                "alt": 614,
                "lat": 24.8425733
            }
        }
    ```
- Teltonika Update Event JSON:
    ```json
    {
            "id": "147187503",
            "type": "Teltonika_Events",
            "text": "Teltonika Events",
            "source": {
                "id": "95147185373",
                "name": "Tracker 356307042441014"
            },
            "time": "2025-01-21T17:20:20.021+05:30",
            "creationTime": "2025-01-21T11:50:19.377Z",
            "lastUpdated": "2025-01-21T11:50:19.377Z",
            "Teltonika_Events": {
                "satellites": 17,
                "altitude": 614,
                "eventID": 0,
                "totalEvents": 21,
                "latitude": 248425733,
                "angle": 149,
                "priority": 0,
                "events": {
                    "44": "0000",
                    "EF": "01",
                    "01": "01",
                    "56": "FFFF",
                    "68": "FFFF",
                    "15": "04",
                    "18": "000B",
                    "19": "7FFF",
                    "09": "00AE",
                    "F0": "01",
                    "6A": "FFFF",
                    "F1": "0000A413",
                    "1A": "7FFF",
                    "B3": "00",
                    "1B": "7FFF",
                    "0B": "00000002183D5F1D",
                    "0E": "0000000042F6A81D",
                    "71": "64",
                    "42": "34E6",
                    "43": "1012",
                    "10": "00270A38"
                },
                "speed": 11,
                "instant": 1733995551000,
                "longitude": 467399983
            },
            "raw_payload":"08120000019579AC9788001BD128570EA7A7E4029800ED120000000E06EF00F0001505C8004501010006B50009B600054231FE430FAF4400000900AE02F10000A41"
        }
    ```
- Tracker Position Update JSON:
    ```json
    {
      "id":"95147185373",
      "c8y_Position": {
        "lng": 46.7399983,
        "alt": 614,
        "lat": 24.8425733
      }
    }
    ```

- Tracker measurement data JSON:
    ```json
    {
      "measurements": [
        {
            "type": "Tracker",
            "source": {
                "id": "30495470"
            },
            "time": "2025-04-09T15:30:44.678+05:30",
            "Tracker": {
                "24": {
                    "value": 93.0,
                    "unit": ""
                },
                "25": {
                    "value": 3000.0,
                    "unit": ""
                },
                "Movement": {
                    "value": 1.0,
                    "unit": ""
                },
                "26": {
                    "value": 3000.0,
                    "unit": ""
                },
                "27": {
                    "value": 3000.0,
                    "unit": ""
                },
                "Ignition": {
                    "value": 1.0,
                    "unit": ""
                },
                "241": {
                    "value": 42003.0,
                    "unit": ""
                },
                "DigitalInput": {
                    "value": 1.0,
                    "unit": ""
                },
                "179": {
                    "value": 0.0,
                    "unit": ""
                },
                "BatteryCurrent": {
                    "value": 0.0,
                    "unit": ""
                },
                "104": {
                    "value": 3000.0,
                    "unit": ""
                },
                "106": {
                    "value": 3000.0,
                    "unit": ""
                },
                "9": {
                    "value": 811.0,
                    "unit": ""
                },
                "86": {
                    "value": 3000.0,
                    "unit": ""
                },
                "GSMSignal": {
                    "value": 4.0,
                    "unit": "dB"
                }
            }
        }
    }
    ```  

## Command Processing
- Upon receiving an IMEI number, the agent:
  1. Retrieves the PENDING commands for the device.
  2. Sends one command at a time using the device’s TCP connection.
  3. Updates the Cumulocity operation status to "EXECUTING" before sending and "SUCCESSFUL" after sending.
  4. If a technical failure occurs, the operation status is not updated, and the agent retries when the IMEI is next received.
- Sample Command (Operation) JSON:
    ```json
    {
            "creationTime": "2025-01-21T10:36:10.583Z",
            "deviceName": "Tracker 356307042441014",
            "deviceId": "95147185373",
            "id": "147189766",
            "status": "PENDING",
            "c8y_Command": {
                "result": null,
                "syntax": null,
                "text": "getver"
            },
            "description": "Execute shell command: CheckVersion& DeviceId"
        }
    ```

## Technical Details
- Built with the **Spring Boot** framework.
- Utilizes **Spring TCP Integration** for efficient TCP connection handling and message buffering.
- Provides a scalable and stateless architecture for managing tracker device connections and data processing.

---

### Getting Started
1. Deploy the microservice on a Kubernetes cluster.
2. Configure the required tenant options in Cumulocity.
3. Ensure tracker devices are configured to connect to the agent via TCP.
4. Verify the setup by checking device connections and operations in the Cumulocity platform.

### Contribution
For any contributions, please follow the standard pull request process and ensure all code changes are tested before submitting.

