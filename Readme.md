# Tracker TCP Agent Microservice

## Description
The Tracker TCP Agent microservice is designed to operate outside the Cumulocity platform due to security restrictions that prevent opening custom TCP ports on the platform. This stateless microservice can be hosted on a Kubernetes cluster, leveraging a load balancer to efficiently handle device loads.

## Connectivity Protocol
- **Device to TCP Agent:** Uses the TCP protocol.
- **Tracking Agent to Cumulocity:** Uses HTTPS protocol for bidirectional communication.

## Explanation
This microservice allows tracker devices to establish TCP socket connections and buffer their data in the Codec8 data format. It supports bidirectional communication.

- When a device opens a TCP connection and sends its IMEI number, the microservice checks for any pending operations for that device and sends them back sequentially using the same TCP connection.
- Command processing occurs only when the device sends its IMEI number. Commands are converted into the Codec12 message format before being sent to the device.

![alt text](image.png)

## How It Works
The microservice uses three maps for temporary data storage:

1. **Bootstrap Credentials**
   - Cumulocity Microservice Bootstrap credentials must be added as a part of properties files

2. **Build & Deploy**
   - This Microservice must be build as Docker imageand could be deployed independently as docker container or any kubernetes cluster 
   - Java17, Maven 3.6 and docker latest must be installed on the machin to build this microservice docker image
   - Using below maven command this could be built
     ```cmd
      mvn clean install
      ```
   - Prerequisite
     - Java 17 or later
     - Cumulocity Java SDK (1020.155.0 or later)
     - A cumulocity microservice must be created and subscribed on the enterprise tenant as well as other subtenants where this Tracker Agent will be interacting. This will be a dummy microservice no zip has to be uploaded it will be used by the Tracker agent to take advantage of C8Y Java SDK multi-tenancy features.
3. **connectionRegistry**
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

4. **imeiToConn**
   - Stores IMEI numbers as keys and DeviceConnectionInfo objects (connection ID, Cumulocity device ID, and IMEI) as values.
   - Populated when a device connects to the agent and used for processing commands and identifying connections for sending commands back to devices.
   ```json
   {
       "imei": {
           "id": "",
           "imei": "",
           "connectionId": ""
       }
   }
   ```

5. **imeiToTenant**
   - Maintains device-tenant mappings, supporting devices connected across multiple tenants.
   - Populated during the agent’s startup based on pre-existing customer-provided mappings or defaults to a specified tenant.
   ```json
   {
       "356307042441014": "t11974744",
       "356307042441013": "t11974744"
   }
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
  1. Looks up the tenant option for the device-tenant mapping.
  2. If not found, registers the device in the default tenant and updates the mapping in tenant options.

## Data Processing
- On receiving Codec8 data:
  1. The agent deserializes the buffered data and converts it into Cumulocity events.
  2. For each Codec8 entry, a Teltonika Location Update Event is created in Cumulocity.
  3. The tracker’s current location is updated in the inventory object.
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
            }
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

