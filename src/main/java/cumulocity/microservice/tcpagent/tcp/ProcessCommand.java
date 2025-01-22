package cumulocity.microservice.tcpagent.tcp;

import com.cumulocity.rest.representation.operation.OperationRepresentation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessCommand {

    private final ObjectMapper mapper;
    private final TcpServerSocketConfiguration tcpServerSocketConfiguration;


    public String extractCommandText(OperationRepresentation operation) {
        try {
            JsonNode node = mapper.readTree(operation.toJSON());
            return node.path("c8y_Command").path("text").asText();
        } catch (Exception e) {
            log.error("Error extracting command text from operation: {}", e.getMessage(), e);
            return null;
        }
    }

    public void sendCommandToDevice(String imei, byte[] command) {
        try {
            String connectionId = GlobalConnectionStore.getImeiToConn().get(imei).getConnectionId();
            tcpServerSocketConfiguration.sendCommandToConnection(connectionId, command);
        } catch (Exception e) {
            log.error("Error sending command to device with IMEI {}: {}", imei, e.getMessage(), e);
        }
    }

}
