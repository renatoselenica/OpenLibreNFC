package me.cominixo.openlibrenfc;

/**
 * Libre 2 FRAM Decryption/Encryption
 * Ported from xDripSwift PreLibre2.swift
 */
public class Libre2Crypto {

    private static int word(int high, int low) {
        return ((high & 0xff) << 8) + (low & 0xff);
    }

    private static int op(int value, int l1, int l2) {
        int res = value >> 2;
        if ((value & 1) != 0) {
            res ^= l2;
        }
        if ((value & 2) != 0) {
            res ^= l1;
        }
        return res & 0xffff;
    }

    private static int[] processCrypto2(int s1, int s2, int s3, int s4, int l1, int l2) {
        int r0 = op(s1, l1, l2) ^ s4;
        int r1 = op(r0, l1, l2) ^ s3;
        int r2 = op(r1, l1, l2) ^ s2;
        int r3 = op(r2, l1, l2) ^ s1;
        int r4 = op(r3, l1, l2);
        int r5 = op(r4 ^ r0, l1, l2);
        int r6 = op(r5 ^ r1, l1, l2);
        int r7 = op(r6 ^ r2, l1, l2);

        return new int[]{
            (r0 ^ r4) & 0xffff,
            (r1 ^ r5) & 0xffff,
            (r2 ^ r6) & 0xffff,
            (r3 ^ r7) & 0xffff
        };
    }

    /**
     * Decrypt or encrypt Libre 2 FRAM data (XOR cipher - same operation for both)
     *
     * @param sensorId 6 bytes UID
     * @param sensorInfo 6+ bytes patch info
     * @param framData encrypted or decrypted FRAM data
     * @return transformed data
     */
    public static byte[] cryptFram(byte[] sensorId, byte[] sensorInfo, byte[] framData) {
        int l1 = 0xa0c5;
        int l2 = 0x6860;
        int l3 = 0x14c6;
        int l4 = 0x0000;

        // Ensure we have at least 344 bytes
        byte[] paddedData = new byte[344];
        System.arraycopy(framData, 0, paddedData, 0, Math.min(framData.length, 344));

        byte[] result = new byte[344];

        for (int i = 0; i < 43; i++) {
            int y = word(sensorInfo[5] & 0xff, sensorInfo[4] & 0xff);
            if (i < 3 || i >= 40) {
                y = 0xcadc;
            }

            int s1;
            if ((sensorInfo[0] & 0xff) == 0xE5 || (sensorInfo[0] & 0xff) == 0xE6) {
                s1 = (word(sensorId[5] & 0xff, sensorId[4] & 0xff) + y + i) & 0xffff;
            } else {
                s1 = (word(sensorId[5] & 0xff, sensorId[4] & 0xff) +
                      (word(sensorInfo[5] & 0xff, sensorInfo[4] & 0xff) ^ 0x44) + i) & 0xffff;
            }

            int s2 = (word(sensorId[3] & 0xff, sensorId[2] & 0xff) + l4) & 0xffff;
            int s3 = (word(sensorId[1] & 0xff, sensorId[0] & 0xff) + (i << 1)) & 0xffff;
            int s4 = (0x241a ^ l3) & 0xffff;

            int[] key = processCrypto2(s1, s2, s3, s4, l1, l2);

            int base = i * 8;
            result[base + 0] = (byte) ((paddedData[base + 0] & 0xff) ^ (key[3] & 0xff));
            result[base + 1] = (byte) ((paddedData[base + 1] & 0xff) ^ ((key[3] >> 8) & 0xff));
            result[base + 2] = (byte) ((paddedData[base + 2] & 0xff) ^ (key[2] & 0xff));
            result[base + 3] = (byte) ((paddedData[base + 3] & 0xff) ^ ((key[2] >> 8) & 0xff));
            result[base + 4] = (byte) ((paddedData[base + 4] & 0xff) ^ (key[1] & 0xff));
            result[base + 5] = (byte) ((paddedData[base + 5] & 0xff) ^ ((key[1] >> 8) & 0xff));
            result[base + 6] = (byte) ((paddedData[base + 6] & 0xff) ^ (key[0] & 0xff));
            result[base + 7] = (byte) ((paddedData[base + 7] & 0xff) ^ ((key[0] >> 8) & 0xff));
        }

        return result;
    }

    /**
     * Reset age in decrypted Libre 2 data
     *
     * @param decryptedData decrypted FRAM data (344 bytes)
     * @param newAge new age in 15-minute intervals
     * @param newStatus new status (3 = Active)
     * @return modified decrypted data with updated CRC
     */
    public static byte[] resetAge(byte[] decryptedData, int newAge, int newStatus) {
        byte[] modified = decryptedData.clone();

        // Set status (byte 4)
        modified[4] = (byte) newStatus;

        // Set age (bytes 316-317, little-endian)
        modified[316] = (byte) (newAge & 0xff);
        modified[317] = (byte) ((newAge >> 8) & 0xff);

        // Recalculate CRC for bytes 26-319
        int[] dataForCrc = new int[294];
        for (int i = 0; i < 294; i++) {
            dataForCrc[i] = modified[26 + i] & 0xff;
        }
        int crc = LibreNfcUtils.crc16(dataForCrc);

        // Set CRC (bytes 24-25, little-endian)
        modified[24] = (byte) (crc & 0xff);
        modified[25] = (byte) ((crc >> 8) & 0xff);

        return modified;
    }
}
