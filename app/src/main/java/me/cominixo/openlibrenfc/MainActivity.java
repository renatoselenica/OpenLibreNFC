package me.cominixo.openlibrenfc;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcV;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.example.openlibrenfc.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import static me.cominixo.openlibrenfc.LibreNfcUtils.bytesToHexStr;
import static me.cominixo.openlibrenfc.LibreNfcUtils.hexStrToBytes;
import static me.cominixo.openlibrenfc.LibreNfcUtils.sendCmd;

public class MainActivity extends AppCompatActivity {

    enum SelectedAction {
        SCAN, RESET_AGE, RESET_AGE_LIBRE2, ACTIVATE, LOAD_DUMP
    }

    private SelectedAction selectedAction = SelectedAction.SCAN;

    private TextView idView;
    private TextView uidView;
    private TextView typeView;
    private TextView ageView;
    private TextView tempView;
    private TextView regionView;
    private TextView statusView;

    private TextView selectedActionView;

    private byte[] memory = new byte[360];
    private byte[] lastPatchInfo = new byte[0];
    private byte[] lastUid = new byte[0];


    public void onScanClick(View view) {

        selectedAction = SelectedAction.SCAN;
        selectedActionView.setText(getString(R.string.selected_action, getString(R.string.scan)));

    }

    public void onResetAgeClick(View view) {

        selectedAction = SelectedAction.RESET_AGE;
        selectedActionView.setText(getString(R.string.selected_action, getString(R.string.reset_age)));

    }

    public void onResetAgeLibre2Click(View view) {

        selectedAction = SelectedAction.RESET_AGE_LIBRE2;
        selectedActionView.setText(getString(R.string.selected_action, "Reset Age (Libre 2)"));

    }

    public void onActivateClick(View view) {

        selectedAction = SelectedAction.ACTIVATE;
        selectedActionView.setText(getString(R.string.selected_action, getString(R.string.start)));

    }

    public void dumpMemory(View view) {

        File file = getTimestampedFile();

        FileOutputStream os;
        try {
            if (!file.exists()) {
                file.createNewFile();
            }

            os = new FileOutputStream(file);
            // Write patch info, UID, and memory on separate lines for easy parsing
            os.write(("PATCH_INFO: " + bytesToHexStr(lastPatchInfo) + "\n").getBytes());
            os.write(("UID: " + bytesToHexStr(lastUid) + "\n").getBytes());
            os.write(("MEMORY: " + bytesToHexStr(memory) + "\n").getBytes());
            os.close();

            Toast.makeText(this, "Memory dumped to " + file.getName(), Toast.LENGTH_LONG).show();

        } catch (IOException e) {

            Toast.makeText(this, "Couldn't dump memory", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }

    }

    private File getTimestampedFile() {
        // Use app's external files dir - no permissions needed
        File dir = new File(getExternalFilesDir(null), "dumps");
        dir.mkdirs();

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dir, "dump_" + timestamp + ".txt");
    }

    public void loadMemory(View view) {

        new AlertDialog.Builder(this)
                .setTitle("Load memory")
                .setMessage("This will overwrite the current memory on the sensor with the most recent memory dump. If you edited the memory dump, make sure the checksums are correct.")
                .setPositiveButton(android.R.string.ok, null)
                .show();

        selectedAction = SelectedAction.LOAD_DUMP;
        selectedActionView.setText(getString(R.string.selected_action, getString(R.string.load_memory)));
    }

    private File getFile() {
        File dir = new File(getExternalFilesDir(null), "dumps");
        dir.mkdirs();
        return new File(dir, "memory_dump.txt");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LibreNfcUtils.updateActivity(this);

        setContentView(R.layout.activity_main);

        idView = findViewById(R.id.libreid);
        uidView = findViewById(R.id.uid);
        typeView = findViewById(R.id.type);
        ageView = findViewById(R.id.age);
        tempView = findViewById(R.id.temp);
        regionView = findViewById(R.id.region);
        statusView = findViewById(R.id.status);
        selectedActionView = findViewById(R.id.selected_action);

        selectedActionView.setText(getString(R.string.selected_action, getString(R.string.scan)));

        idView.setText("");
        uidView.setText("");
        typeView.setText("");
        ageView.setText("");
        tempView.setText("");
        regionView.setText("");
        statusView.setText("");
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent nfcIntent = new Intent(this, getClass());
        nfcIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, flags);
        IntentFilter[] intentFiltersArray = new IntentFilter[]{};
        String[][] techList = new String[][]{{android.nfc.tech.Ndef.class.getName()}, {android.nfc.tech.NdefFormatable.class.getName()}};
        NfcAdapter nfcAdpt = NfcAdapter.getDefaultAdapter(this);
        nfcAdpt.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techList);

    }

    @Override
    public void onNewIntent(Intent intent) {
        Tag nfcTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);


        NfcV handle = NfcV.get(nfcTag);


        byte[] getIdCmd = {
                (byte) 0x02, // Flag for un-addressed communication
                (byte) 0xa1, // Get Patch Info
                (byte) 0x07  // Vendor Identifier
        };

        byte[] getUidCmd = {
                (byte) 0x26,
                (byte) 0x01,
                (byte) 0x00
        };


        byte[] receivedId = sendCmd(handle, getIdCmd);

        byte[] receivedUid = sendCmd(handle, getUidCmd);


        if (receivedId.length != 0 && receivedUid.length != 0) {

            // Remove zeros
            receivedId = Arrays.copyOfRange(receivedId, 1, receivedId.length);
            receivedUid = Arrays.copyOfRange(receivedUid, 2, receivedUid.length-2);

            byte[] typeIdentifier = Arrays.copyOfRange(receivedId, 0, 3);

            String libreType;

            boolean isLibre2 = false;
            if (Arrays.equals(typeIdentifier, LibreConstants.LIBRE1_NEW_ID)) {
                libreType = "Libre 1 New";
            } else if (Arrays.equals(typeIdentifier, LibreConstants.LIBRE1_OLD_ID)) {
                libreType = "Libre 1 Old";
            } else if (Arrays.equals(typeIdentifier, LibreConstants.LIBRE1_JAPAN_ID)) {
                libreType = "Libre 1 Japan";
            } else if (Arrays.equals(typeIdentifier, LibreConstants.LIBRE2_EU_ID)) {
                libreType = "Libre 2 EU";
                isLibre2 = true;
            } else if (Arrays.equals(typeIdentifier, LibreConstants.LIBRE2_US_ID)) {
                libreType = "Libre 2 US";
                isLibre2 = true;
            } else if (Arrays.equals(typeIdentifier, LibreConstants.LIBRE2_EU_NEW_ID)) {
                libreType = "Libre 2 EU (new)";
                isLibre2 = true;
            } else {
                libreType = "Unknown (" + bytesToHexStr(typeIdentifier).trim() + ")";
            }

            memory = LibreNfcUtils.readMemory(handle);

            // Save patch info and UID for decryption research
            lastPatchInfo = receivedId;
            lastUid = receivedUid;

            if (memory.length == 0) {
                return;
            }

            switch (selectedAction) {

                case RESET_AGE:
                    memory[317] = 0;
                    memory[316] = 0;

                    // Convert to int array first

                    int[] memoryInt = new int[memory.length];

                    for (int i = 0; i < memory.length; i++) {

                        memoryInt[i] = memory[i] & 0xff;

                    }

                    int out = LibreNfcUtils.crc16(Arrays.copyOfRange(memoryInt, 26, 294+26));


                    byte[] crc = ByteBuffer.allocate(4).putInt(out).array();

                    memory[24] = crc[3];
                    memory[25] = crc[2];

                    LibreNfcUtils.unlock(handle);

                    LibreNfcUtils.writeMemory(handle, memory);

                    LibreNfcUtils.lock(handle);

                    memory = LibreNfcUtils.readMemory(handle);

                    if (memory.length == 0) {
                        return;
                    }
                    break;

                case RESET_AGE_LIBRE2:
                    // Libre 2 age reset - decrypt, modify, re-encrypt in single session
                    if (memory.length < 336) {
                        Toast.makeText(this, "Memory too short for Libre 2", Toast.LENGTH_SHORT).show();
                        break;
                    }

                    // Use patch info (receivedId) and UID (receivedUid) from current session
                    byte[] decrypted = Libre2Crypto.cryptFram(receivedUid, receivedId, memory);

                    // Log original values
                    int origStatus = decrypted[4] & 0xff;
                    int origAge = (decrypted[316] & 0xff) + ((decrypted[317] & 0xff) << 8);
                    System.out.println("Libre2 Reset - Original status: " + origStatus + ", age: " + origAge);

                    // Reset age to 96 intervals (1 day) and status to 3 (Active)
                    byte[] modified = Libre2Crypto.resetAge(decrypted, 96, 3);

                    // Re-encrypt with same session's patch info
                    byte[] reEncrypted = Libre2Crypto.cryptFram(receivedUid, receivedId, modified);

                    // Attempt to write
                    LibreNfcUtils.unlock(handle);
                    LibreNfcUtils.writeMemory(handle, reEncrypted);
                    LibreNfcUtils.lock(handle);

                    // Read back to verify
                    memory = LibreNfcUtils.readMemory(handle);
                    if (memory.length == 0) {
                        Toast.makeText(this, "Failed to read back after write", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Verify by decrypting
                    byte[] verifyDecrypt = Libre2Crypto.cryptFram(receivedUid, receivedId, memory);
                    int newStatus = verifyDecrypt[4] & 0xff;
                    int newAge = (verifyDecrypt[316] & 0xff) + ((verifyDecrypt[317] & 0xff) << 8);
                    System.out.println("Libre2 Reset - New status: " + newStatus + ", age: " + newAge);

                    Toast.makeText(this, "Reset attempted. Status: " + origStatus + "->" + newStatus + ", Age: " + origAge + "->" + newAge, Toast.LENGTH_LONG).show();
                    break;

                case ACTIVATE:
                    byte[] activateCmd = new byte[]
                    {
                        (byte) 0x02,
                        (byte) 0xA0,
                        (byte) 0x07,
                        (byte) 0XC2,
                        (byte) 0xAD,
                        (byte) 0x75,
                        (byte) 0x21
                    };

                    sendCmd(handle, activateCmd);
                    break;
                case LOAD_DUMP:
                    StringBuilder text = new StringBuilder();

                    File file = getFile();

                    try {
                        BufferedReader br = new BufferedReader(new FileReader(file));
                        String line;

                        while ((line = br.readLine()) != null) {
                            text.append(line);
                            text.append('\n');
                        }
                        br.close();
                    }
                    catch (IOException e) {
                        vibrate();

                        Toast.makeText(this, "Couldn't read the memory dump, is the file there?", Toast.LENGTH_SHORT).show();

                        e.printStackTrace();
                        return;
                    }

                    byte[] newMemory = hexStrToBytes(text.toString());


                    if (newMemory == null) {
                        vibrate();
                        new AlertDialog.Builder(this)
                                .setTitle("Memory load failed!")
                                .setMessage("The memory dump length was not the expected one! Check your memory_dump.txt")
                                .setPositiveButton(android.R.string.ok, null)
                                .show();

                        return;
                    }

                    memory = newMemory;

                    LibreNfcUtils.unlock(handle);

                    LibreNfcUtils.writeMemory(handle, memory);

                    LibreNfcUtils.lock(handle);


            }


            // Regular scan stuff - with bounds checking for Libre 2 (smaller memory)
            int memLen = memory.length;

            float age = -1;
            if (memLen > 317) {
                age = 256 * (memory[317] & 0xFF) + (memory[316] & 0xFF);
            }

            int region = -1;
            if (memLen > 323) {
                region = memory[323];
            }

            int status = memLen > 4 ? memory[4] : -1;

            String statusString;

            switch (status) {
                case 1:
                    statusString = "New (not activated)";
                    break;
                case 2:
                    statusString = "In warmup";
                    break;
                case 3:
                    statusString = "Active";
                    break;
                case 5:
                    statusString = "Expired";
                    break;
                case 6:
                    statusString = "Error";
                    break;
                default:
                    statusString = "Unknown";
                    break;
            }

            System.out.println(bytesToHexStr(memory));
            String id = bytesToHexStr(receivedId);

            String uid = bytesToHexStr(receivedUid);

            double tempCelsius = -1;
            if (memLen > 130) {  // Need at least enough bytes for temperature data
                int trendIndex = memory[26] & 0xFF;  // Treat as unsigned
                int index = (trendIndex - 1) & 0x0F;  // Keep in 0-15 range

                int tempIdx1 = index * 6 + 32;
                int tempIdx2 = index * 6 + 31;
                if (memLen > tempIdx1 && memLen > tempIdx2 && tempIdx1 >= 0 && tempIdx2 >= 0) {
                    float temp = (256 * (memory[tempIdx1] & 0xFF) + (memory[tempIdx2] & 0xFF)) & 0x3fff;
                    // https://type1tennis.blogspot.com/2017/09/libre-other-bytes-well-some-of-them-at.html
                    tempCelsius = Math.round((temp*0.0027689+9.53)*100.0)/100.0;
                }
            }

            idView.setText(getString(R.string.libreid, id));
            uidView.setText(getString(R.string.uid, uid));
            typeView.setText(getString(R.string.type, libreType + " (" + memLen + " bytes)"));
            ageView.setText(age >= 0 ? getString(R.string.age, age/1440) : "Age: N/A (encrypted)");
            tempView.setText(tempCelsius >= 0 ? getString(R.string.temp, tempCelsius) : "Temp: N/A");
            regionView.setText(region >= 0 ? getString(R.string.region, region) : "Region: N/A");
            statusView.setText(getString(R.string.status, status, statusString));

            Toast.makeText(this, "Scanned Successfully", Toast.LENGTH_SHORT).show();

            vibrate();
        }

    }

    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.vibrate(500);
        }
    }


}
