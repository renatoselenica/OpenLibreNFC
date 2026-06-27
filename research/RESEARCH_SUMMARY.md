# Libre 2 EU Sensor Age Reset Research

## Overview

This document summarizes the research conducted to understand if and how a Libre 2 EU glucose sensor's age can be reset to extend its lifespan beyond the 14-day limit.

**Conclusion: Age reset is NOT currently possible on Libre 2 due to enhanced security measures.**

## Key Findings

### 1. Libre 2 Memory Structure

- **Memory Size**: 336 bytes readable via NFC (42 blocks × 8 bytes)
- **Encryption**: Unlike Libre 1, the entire FRAM memory is encrypted using a XOR-based cipher
- **Key Derivation**: Encryption keys are derived from:
  - Sensor UID (6 bytes)
  - Patch Info (6 bytes, last 2 bytes change every NFC session!)

### 2. Sensor Types Discovered

| Type ID | Sensor |
|---------|--------|
| `9D 08 30` | Libre 2 EU (older) |
| `C5 09 30` | Libre 2 EU (newer variant) |
| `A2 08 03` | Libre 2 US |

### 3. Decryption Algorithm

Successfully ported from [xDripSwift](https://github.com/JohanDegraeve/xdripswift) PreLibre2.swift:

```python
# Key constants
l1 = 0xa0c5
l2 = 0x6860
l3 = 0x14c6
l4 = 0x0000

# XOR-based cipher using sensor UID and patch info
# See libre2_decrypt.py for full implementation
```

**Decryption works correctly** - we successfully decrypted sensor dumps and read status/age values.

### 4. Decrypted Memory Structure (Libre 1 format)

| Bytes | Content |
|-------|---------|
| 0-3 | Header |
| 4 | Status (1=New, 2=Warmup, 3=Active, 4=Shutdown?, 5=Expired, 6=Error) |
| 24-25 | CRC16 (little-endian) |
| 26 | Trend index |
| 316-317 | Age in 15-minute intervals (little-endian) |
| 323 | Region |

### 5. Critical Discovery: Dynamic Patch Info

**The last 2 bytes of patch info change with every NFC interaction!**

This means:
- Data encrypted with `patch_info_A` cannot be correctly read if the sensor expects `patch_info_B`
- Pre-computing encrypted reset data offline will NOT work
- **Reset must happen in a single NFC session**: read → decrypt → modify → re-encrypt → write

## Experiments Conducted

### Sensors Used

1. **Expired Sensor** (UID: D5 37 70 19 00 A4)
   - Decrypted Status: 4 (shutdown/terminated)
   - Decrypted Age: 20,781 intervals (~216 days)

2. **Warming Up Sensor** (UID: 3F 8E 73 19 00 A4)
   - Decrypted Status: 3 (Active)
   - Decrypted Age: 21 intervals (~5 hours)

### In-Session Reset Attempt

Implemented full in-session reset in the Android app:
1. Read memory + patch info in single NFC session
2. Decrypt with current session's patch info
3. Modify status (→3) and age (→96)
4. Recalculate CRC
5. Re-encrypt with same patch info
6. Attempt to write back immediately

**Results from logs:**
```
Libre2 Reset - Original status: 4, age: 20781
Unlock response: 01 01 (length: 2)
Unlock FAILED with error: 0x01
Write block 0 response: 01 12
Write block 0 FAILED with error: 0x01
... (all 43 blocks failed)
Libre2 Reset - New status: 4, age: 20781  (unchanged)
```

**Error codes:**
- `0x01` = Error flag (ISO 15693)
- `0x12` = "Block locked" - write protection is active

### Key Finding: Libre 1 Password Does NOT Work on Libre 2

| Feature | Libre 1 | Libre 2 |
|---------|---------|---------|
| Unlock password | `0xC2 0xAD 0x75 0x21` ✓ | **Rejected** ✗ |
| Memory writes | Possible after unlock | Blocked (0x01 0x12) |
| Age reset | Works | **Not possible** |

## LibreLink APK Reverse Engineering

### Decompilation Process

1. Pulled APK from phone: `com.freestylelibre.app.de`
2. Decompiled with jadx
3. Analyzed NFC communication code

### Key Findings from Decompiled Code

**NFC Command Structure** (from `DefaultNfcRfModule.java`):
```java
// Activation command format: {0x02, command_byte, 0x07, ...password, ...extra_data}
public boolean sendActivationCommand(NfcOsHandle handle, byte b, byte[] bArr, byte[] bArr2) {
    byte[] cmd = {2, b, 7};  // 0x02, command, 0x07
    // Appends bArr (password) and optionally bArr2 (extra data)
}
```

**Critical Discovery: Native Code**

The activation command and payload generation are in **compiled native code**:

```java
// From DataProcessingNative.java
static {
    System.loadLibrary("DataProcessing");
}

private native byte getActivationCommand(int i, byte[] bArr);
private native byte[] getActivationPayload(int i, byte[] bArr, byte[] bArr2, byte b);
```

The actual password/unlock logic is hidden in `libDataProcessing.so` - a compiled C/C++ library that cannot be easily decompiled.

### Native Libraries in APK

| Library | Size | Purpose |
|---------|------|---------|
| `libDataProcessing.so` | ~338KB | Activation commands, payloads, encryption |
| `libSecureKeyBoxJava.so` | ~1.5MB | Cryptographic operations |
| `libMathRuntimeChecks.so` | ~10KB | Math utilities |

### Libre 2 Security Model

From [Flameeyes' reverse engineering research](https://flameeyes.blog/2020/02/20/freestyle-libre-2-ghidra-notes/):

- Libre 2 uses **challenge-response authentication**, not a simple password
- Two separate key sets:
  - **Authentication phase**: Challenge-response based on device serial number
  - **Session phase**: Separate encryption and MAC keys
- The patch serial number is NOT used for encryption of reader's access
- Subcommand-based initialization with state machine

## Open Source App Analysis

### xDrip (Android)
- [NFCReaderX.java](https://github.com/NightscoutFoundation/xDrip/blob/master/app/src/main/java/com/eveningoutpost/dexdrip/NFCReaderX.java)
- **Read-only operations** for Libre 2
- No write/unlock commands implemented
- Relies on OOP2 helper app for decryption

### DiaBLE (iOS)
- [github.com/gui-dos/DiaBLE](https://github.com/gui-dos/DiaBLE)
- Has unlock command but states: "Activating a Libre 2 is not supported"
- Throws `NFCError.commandNotSupported` for security generation 2 sensors

### Conclusion from Open Source

No open-source project has successfully implemented Libre 2 write operations. All implementations are read-only.

## Files Created

| File | Purpose |
|------|---------|
| `libre2_decrypt.py` | Python decryption implementation |
| `libre2_reset.py` | Offline reset attempt (doesn't work due to dynamic patch info) |
| `Libre2Crypto.java` | Java port for Android app |
| `libre2_expired_full.txt` | Memory dump of expired sensor |
| `libre2_warmup_full.txt` | Memory dump of warming up sensor |

## Android App Updates

Added to OpenLibreNFC app:
- Libre 2 sensor detection (type IDs)
- "Reset (L2)" button for in-session reset attempt
- `Libre2Crypto.java` - encryption/decryption
- Timestamped memory dumps with patch info and UID
- Debug logging for unlock/write responses

## Why Age Reset is Blocked

1. **No simple password**: Libre 2 doesn't use a static 4-byte password like Libre 1
2. **Challenge-response auth**: Requires cryptographic handshake
3. **Keys in native code**: `libDataProcessing.so` contains obfuscated key generation
4. **Device-specific**: Payloads generated from sensor UID + device keys
5. **Write protection**: All blocks return "locked" error without proper authentication

## Potential Future Research

### Option 1: Reverse Engineer libDataProcessing.so
- Use Ghidra or IDA Pro to disassemble the native library
- Find `getActivationCommand()` and `getActivationPayload()` functions
- Extract key generation algorithm
- **Effort**: High (obfuscated code, cryptographic operations)

### Option 2: Hardware Attack
- JTAG/debug interface on the RF430 chip
- Direct FRAM access bypassing NFC
- **Risk**: Destructive, requires specialized equipment

### Option 3: Protocol Sniffing
- Capture NFC traffic during official app activation
- Analyze challenge-response sequence
- Requires SDR equipment or specialized NFC sniffer
- **Note**: Original Libre 1 password was discovered this way

### Option 4: Wait for Community
- Someone may eventually crack it and publish
- Abbott may face legal pressure to allow interoperability
- New sensor generations (Libre 3) may have different vulnerabilities

## Resources

- [xDripSwift](https://github.com/JohanDegraeve/xdripswift) - iOS app with Libre 2 decryption
- [DiaBLE](https://github.com/gui-dos/DiaBLE) - iOS NFC/BLE research
- [xDrip](https://github.com/NightscoutFoundation/xDrip) - Android CGM app
- [Flameeyes Blog](https://flameeyes.blog/2020/02/20/freestyle-libre-2-ghidra-notes/) - Libre 2 Ghidra analysis
- [OpenFreeStyle](https://github.com/captainbeeheart/openfreestyle) - Libre 1 reverse engineering
- [glucometer-protocols](https://github.com/glucometers-tech/glucometer-protocols/issues/8) - Protocol research

## Disclaimer

This research is for educational purposes only. Modifying medical devices carries significant risks. Do not use modified sensors for medical decisions. Abbott has used DMCA takedowns against similar projects in the past.

---

*Last updated: January 2025*
