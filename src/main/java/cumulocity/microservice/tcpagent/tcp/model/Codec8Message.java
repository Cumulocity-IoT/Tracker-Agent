package cumulocity.microservice.tcpagent.tcp.model;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.Data;

import java.nio.ByteBuffer;

@Data
public class Codec8Message {

    private byte protocol;
    private byte avlDataLength;
    private AvlEntry[] avlData;

    public Codec8Message(byte[] data) {
        ByteBuffer dataBuffer = ByteBuffer.wrap(data);
        this.protocol = dataBuffer.get();
        this.avlDataLength = dataBuffer.get();
        int avlDataLengthInt = BytesUtil.toUnsigned(avlDataLength);
        this.avlData = new AvlEntry[avlDataLengthInt];
        for(int i = 0;i<avlDataLengthInt;i++) {
            avlData[i] = new AvlEntry(dataBuffer);
        }
    }
}
