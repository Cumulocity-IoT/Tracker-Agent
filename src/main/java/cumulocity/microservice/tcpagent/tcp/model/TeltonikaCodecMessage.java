package cumulocity.microservice.tcpagent.tcp.model;

import cumulocity.microservice.tcpagent.tcp.util.BytesUtil;
import lombok.Data;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Data
public class TeltonikaCodecMessage {

    private String protocol;
    private byte avlDataLength;
    private AvlEntry[] avlData;

    public TeltonikaCodecMessage(byte[] data) {
        ByteBuffer dataBuffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        this.protocol = BytesUtil.byteToHexString(dataBuffer.get());
        this.avlDataLength = dataBuffer.get();
        int avlDataLengthInt = BytesUtil.toUnsigned(avlDataLength);
        this.avlData = new AvlEntry[avlDataLengthInt];
        for(int i = 0;i<avlDataLengthInt;i++) {
            avlData[i] = new AvlEntry(dataBuffer);
        }
    }

}
