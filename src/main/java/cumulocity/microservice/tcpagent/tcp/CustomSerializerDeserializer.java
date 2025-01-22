package cumulocity.microservice.tcpagent.tcp;

import cumulocity.microservice.tcpagent.tcp.model.TcpMessage;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.serializer.Deserializer;
import org.springframework.core.serializer.Serializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@Slf4j
public class  CustomSerializerDeserializer implements Serializer<byte[]>, Deserializer<TcpMessage> {

    @NonNull
    @Override
    public TcpMessage deserialize(InputStream inputStream) throws IOException {
        // IMEI
        log.info("Message Received");
        short imeiLength = ByteBuffer.wrap(inputStream.readNBytes(2)).getShort();
        if (imeiLength != 0) {
            byte[] imei = inputStream.readNBytes(imeiLength);
            log.info("IMEI # {}", new String(imei, StandardCharsets.UTF_8));
            return new TcpMessage(TcpMessage.MessageType.IMEI, imei);
        }

        // DATA
        short secondTwoBytes = ByteBuffer.wrap(inputStream.readNBytes(2)).getShort();
        if ( secondTwoBytes != 0)
            log.warn("Message expected to start with four 0x00 bytes but was instead 0x{}", String.format("%08x", secondTwoBytes));

        int dataFieldLength = ByteBuffer.wrap(inputStream.readNBytes(4)).getInt();
        byte[] data = inputStream.readNBytes(dataFieldLength);
        log.debug("data # {}", new String(data, StandardCharsets.UTF_8));
        inputStream.readNBytes(4); // Disregard the CRC checksum TODO: Implement CRC check
        return new TcpMessage(TcpMessage.MessageType.DATA, data);
    }

    @Override
    public void serialize(@NotNull byte[] message, OutputStream outputStream) throws IOException {
        outputStream.write(message);
        outputStream.flush();
    }

}
