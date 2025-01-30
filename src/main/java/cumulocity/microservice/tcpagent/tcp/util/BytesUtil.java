package cumulocity.microservice.tcpagent.tcp.util;

import java.nio.charset.StandardCharsets;

public class BytesUtil {

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final int[] CRC16_TABLE = new int[256];

    static {
        // Precompute CRC16 lookup table for efficiency
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
            CRC16_TABLE[i] = crc;
        }
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hexString.append(HEX_ARRAY[(b >> 4) & 0xF])
                     .append(HEX_ARRAY[b & 0xF]);
        }
        return hexString.toString();
    }

    public static int toUnsigned(byte b) {
        return b & 0xFF;
    }

    public static int toUnsigned(short s) {
        return s & 0xFFFF;
    }

    public static String fromByteArray(byte[] bytes) {
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : "";
    }

    public static byte hexToByte(String hexString) {
        if (hexString == null || hexString.length() != 2) {
            throw new IllegalArgumentException("Hex string must be exactly 2 characters long.");
        }
        return (byte) Integer.parseInt(hexString, 16);
    }

    public static byte[] hexToByteArray(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];

        String[] hexValues = hex.split(",");
        byte[] byteArray = new byte[hexValues.length];

        for (int i = 0; i < hexValues.length; i++) {
            byteArray[i] = hexToByte(hexValues[i].trim());
        }
        return byteArray;
    }

    public static int calculateCRC16(byte[] data) {
        return calculateCRC16(data, 0, data.length);
    }

    public static int calculateCRC16(byte[] data, int offset, int length) {
        if (data == null || data.length == 0 || offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Invalid data or range for CRC16 calculation.");
        }

        int crc = 0x0000;  // Initial CRC value
        for (int i = offset; i < offset + length; i++) {
            crc = (crc >>> 8) ^ CRC16_TABLE[(crc ^ data[i]) & 0xFF];
        }
        return crc;
    }
}
