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
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

@Slf4j
public class CustomSerializerDeserializer
        implements Serializer<byte[]>, Deserializer<TcpMessage> {

    @NonNull
    @Override
    public TcpMessage deserialize(InputStream inputStream) throws IOException {

        log.info("Message Received");

        byte[] firstTwo = readFully(inputStream, 2);
        short firstShort = ByteBuffer.wrap(firstTwo).getShort();

        // ------------------------------------------------
        // IMEI Packet (First message after connect)
        // ------------------------------------------------
        if (firstShort > 0) {
            byte[] imei = readFully(inputStream, firstShort);
            log.info("IMEI: {}", new String(imei, StandardCharsets.UTF_8));
            return new TcpMessage(TcpMessage.MessageType.IMEI, imei);
        }

        // ------------------------------------------------
        // DATA Packet (Codec 8 or 8E)
        // ------------------------------------------------

        // Read remaining 2 bytes of preamble
        readFully(inputStream, 2);

        // Read 4 byte AVL data length
        byte[] lengthBytes = readFully(inputStream, 4);
        int dataLength = ByteBuffer.wrap(lengthBytes).getInt();

        log.info("Data length: {}", dataLength);

        // Read AVL data
        log.info("Expecting total packet bytes: {}", 8 + dataLength + 4);
        byte[] data = readFully(inputStream, dataLength);
        log.info("Actually read bytes: {}", data.length);
        log.info("Bytes available before CRC read: {}", inputStream.available());
        // Read CRC (4 bytes)
        byte[] crc = new byte[4];
        int read = inputStream.read(crc);

        if (read < 4) {
            log.warn("CRC not fully received. Received only {} bytes", read);
        }

        return new TcpMessage(TcpMessage.MessageType.DATA, data);
    }

    @Override
    public void serialize(@NotNull byte[] message, OutputStream outputStream) throws IOException {
        outputStream.write(message);
        outputStream.flush();
    }

    /**
     * Ensures we read exactly N bytes from stream.
     * Prevents partial read issues that cause timeouts.
     */
    private byte[] readFully(InputStream in, int length) throws IOException {

        byte[] buffer = new byte[length];
        int totalRead = 0;

        while (totalRead < length) {

            log.info("Waiting for {} more bytes...", length - totalRead);

            int read = in.read(buffer, totalRead, length - totalRead);

            log.info("Read {} bytes", read);

            if (read == -1) {
                throw new IOException("Stream closed while reading");
            }

            totalRead += read;
        }

        return buffer;
    }
}