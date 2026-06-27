package me.cominixo.openlibrenfc;

class LibreConstants {

    static final byte[] LIBRE1_NEW_ID = {
            (byte) 0xA2,
            (byte) 0x08,
            (byte) 0x00
    };

    static final byte[] LIBRE1_OLD_ID = {
            (byte) 0xE9,
            (byte) 0x00,
            (byte) 0x00
    };

    static final byte[] LIBRE1_JAPAN_ID = {
            (byte) 0x00,
            (byte) 0x00,
            (byte) 0x04
    };

    // Libre 2 sensor IDs (EU and others)
    static final byte[] LIBRE2_EU_ID = {
            (byte) 0x9D,
            (byte) 0x08,
            (byte) 0x30
    };

    static final byte[] LIBRE2_US_ID = {
            (byte) 0xA2,
            (byte) 0x08,
            (byte) 0x03
    };

    // Libre 2 EU (newer variant)
    static final byte[] LIBRE2_EU_NEW_ID = {
            (byte) 0xC5,
            (byte) 0x09,
            (byte) 0x30
    };

    static final int MEMORY_SIZE = 360;

    // Password to unlock/lock the chip
    static final byte[] PASSWORD = {
            (byte) 194,
            (byte) 173,
            (byte) 117,
            (byte) 33
    };

}
