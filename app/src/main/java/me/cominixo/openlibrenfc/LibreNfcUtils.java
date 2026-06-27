package me.cominixo.openlibrenfc;

import android.app.Activity;
import android.nfc.tech.NfcV;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class LibreNfcUtils {

    private static WeakReference<Activity> mainActivityRef;
    public static void updateActivity(Activity activity) {
        mainActivityRef = new WeakReference<>(activity);
    }

    private static final String TAG = "LibreNfcUtils";

    // Reads the F-RAM memory from the Libre
    public static byte[] readMemory(NfcV handle) {

        byte[] received = new byte[LibreConstants.MEMORY_SIZE];

        Log.d(TAG, "Starting memory read...");

        int bytesRead = 0;

        // Read blocks in chunks of 3, but handle the end gracefully
        for (int i = 0; i < 45; i += 3){

            // Calculate how many blocks we can read (max 3, but less at the end)
            int blocksToRead = Math.min(3, 45 - i);

            byte[] cmd = {
                    (byte) 0x02, // Flag for un-addressed communication
                    (byte) 0x23, // Read Multiple Blocks
                    (byte) i, // Start block
                    (byte) (blocksToRead - 1) // Number of blocks (0-indexed)
            };

            byte[] response = sendCmd(handle, cmd);

            int expectedLen = 1 + (blocksToRead * 8); // 1 status byte + data

            Log.d(TAG, "Block " + i + " (reading " + blocksToRead + ") response length: " + response.length + " data: " + bytesToHexStr(response));

            if (response.length >= 2 && response[0] == 0x00) {
                // Success - copy data (skip first status byte)
                int dataToCopy = Math.min(response.length - 1, LibreConstants.MEMORY_SIZE - bytesRead);
                System.arraycopy(response, 1, received, bytesRead, dataToCopy);
                bytesRead += dataToCopy;
            } else if (response.length >= 2 && response[0] == 0x01) {
                // Error response - sensor may have fewer blocks, stop here
                Log.w(TAG, "Sensor returned error at block " + i + ", stopping read. Bytes read so far: " + bytesRead);
                break;
            } else if (response.length == 0) {
                Log.e(TAG, "Empty response at block " + i);
                break;
            }
        }

        Log.d(TAG, "Memory read complete. Total bytes: " + bytesRead);

        // Return what we got (may be partial for Libre 2)
        if (bytesRead > 0) {
            byte[] result = new byte[bytesRead];
            System.arraycopy(received, 0, result, 0, bytesRead);
            return result;
        }

        return new byte[0];
    }

    public static void writeMemory(NfcV handle, byte[] newMemory) {
        // Calculate number of blocks based on actual memory size
        int numBlocks = Math.min(43, newMemory.length / 8);
        Log.d(TAG, "writeMemory: writing " + numBlocks + " blocks (" + newMemory.length + " bytes)");

        for (int index = 0; index < numBlocks; index++)
        {

            byte[] newData = new byte[8];

            for (int i = 0; i < 8; i++) {
                newData[i] = newMemory[index*8+i];

            }

            byte[] cmd =
            {
                (byte) 2, // Flags
                (byte) 0x21, // Write single block
                (byte) index, // Block to write
            };

            byte[] cmdWithBlocks = Arrays.copyOf(cmd, cmd.length + newData.length);

            System.arraycopy(newData, 0, cmdWithBlocks, cmd.length, newData.length);

            byte[] response = sendCmd(handle, cmdWithBlocks);
            // Log write response - 0x00 = success, 0x01 = error
            if (response.length > 0) {
                Log.d(TAG, "Write block " + index + " response: " + bytesToHexStr(response));
                if (response[0] != 0x00) {
                    Log.e(TAG, "Write block " + index + " FAILED with error: " + String.format("0x%02X", response[0]));
                }
            } else {
                Log.e(TAG, "Write block " + index + " got empty response");
            }
        }
    }

    public static byte[] sendCmd(NfcV handle, byte[] cmd) {

        long startTime = System.currentTimeMillis();

        while (true) {
            try {

                if (handle.isConnected()) {
                    handle.close();
                }

                handle.connect();
                byte[] received = handle.transceive(cmd);
                handle.close();

                return received;

            } catch (IOException ioException) {
                if (System.currentTimeMillis() > startTime + 3000) {
                    Toast.makeText(mainActivityRef.get(), "Scan timed out!", Toast.LENGTH_SHORT).show();
                    return new byte[0];
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interruptedException) {
                    return new byte[0];
                }

            }
        }

    }


    public static String bytesToHexStr(byte[] bytes) {
        StringBuilder out = new StringBuilder();

        for (Byte b : bytes) {
            out.append(String.format("%02X", b)).append(" ");
        }

        return out.toString();

    }

    public static byte[] hexStrToBytes(String string) {
        byte[] bytes = new byte[360];

        List<String> cleanString = new ArrayList<>();

        for (String s : string.split(" ")) {
            if (!s.trim().isEmpty())
                cleanString.add(s.trim());
        }

        if (cleanString.size() != 360) {
            return null;
        }

        for (int i = 0; i < cleanString.size(); i++) {

            int byteInt = Integer.parseInt(cleanString.get(i), 16);
            bytes[i] = (byte) byteInt;

        }
        return bytes;
    }

    public static int crc16 (int[] data) {
        int crc = 0x0000FFFF;

        for (int i = 0; i < data.length; i++) {
            crc = ((crc >> 8) & 0x0000ffff) | ((crc <<  8) & 0x0000ffff);
            crc ^= bitRev((byte)data[i]);

            crc ^= (((crc & 0xff) >> 4) & 0x0000ffff);
            crc ^= ((crc << 12) & 0x0000ffff);
            crc ^=(((crc & 0xff) << 5) & 0x0000ffff);
        }
        return crc;

    }

    public static int bitRev(byte data) {
        return ((data << 7) & 0x80) | ((data << 5)& 0x40) | (data << 3) & 0x20 | (data << 1) &0x10 | (data >> 7) & 0x01 | (data >> 5) &0x02 | (data >> 3) & 0x04 | (data >> 1) &0x08;
    }

    public static void unlock(NfcV handle)
    {
        byte[] cmd =
        {
            (byte) 0x02, // Flag for un-addressed communication
            (byte) 0xA4, // Unlock
            (byte) 0x07  // Vendor identifier
        };


        byte[] cmdWithPassowrd = Arrays.copyOf(cmd, cmd.length + LibreConstants.PASSWORD.length);

        System.arraycopy(LibreConstants.PASSWORD, 0, cmdWithPassowrd, cmd.length, LibreConstants.PASSWORD.length);

        byte[] response = sendCmd(handle, cmdWithPassowrd);
        Log.d(TAG, "Unlock response: " + bytesToHexStr(response) + " (length: " + response.length + ")");
        if (response.length > 0 && response[0] != 0x00) {
            Log.e(TAG, "Unlock FAILED with error: " + String.format("0x%02X", response[0]));
        }
    }

    public static void lock(NfcV handle) {

        byte[] cmd =
        {
            (byte) 0x02, // Flag for un-addressed communication
            (byte) 0XA2, // Lock
            (byte) 0x07  // Vendor identifier
        };

        byte[] cmdWithPassowrd = Arrays.copyOf(cmd, cmd.length + LibreConstants.PASSWORD.length);

        System.arraycopy(LibreConstants.PASSWORD, 0, cmdWithPassowrd, cmd.length, LibreConstants.PASSWORD.length);

        byte[] response = sendCmd(handle, cmdWithPassowrd);
        Log.d(TAG, "Lock response: " + bytesToHexStr(response) + " (length: " + response.length + ")");
    }
}
