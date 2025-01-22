package cumulocity.microservice.tcpagent.tcp.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BytesUtilTest {

    @Test
    void testBytesToHex() {
        byte[] data = new byte[]{(byte) 0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF};
        assertEquals("DEADBEEF", BytesUtil.bytesToHex(data));
    }

    @Test
    void testToUnsignedFromByte() {
        byte b = (byte) 0xFF;
        assertEquals(255, BytesUtil.toUnsigned(b));
    }

    @Test
    void testToUnsignedFromShort() {
        short s = ByteBuffer.wrap(new byte[]{(byte) 0xFF, (byte) 0xFF}).getShort();
        assertEquals(65535, BytesUtil.toUnsigned(s));
    }
}