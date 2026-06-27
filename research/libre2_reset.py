#!/usr/bin/env python3
"""
Libre 2 Sensor Age Reset Tool
Decrypts FRAM, modifies age/status, recalculates CRC, re-encrypts
"""

def word(high: int, low: int) -> int:
    return ((high & 0xff) << 8) + (low & 0xff)

def op(value: int, l1: int, l2: int) -> int:
    res = value >> 2
    if value & 1:
        res ^= l2
    if value & 2:
        res ^= l1
    return res & 0xffff

def process_crypto2(s1: int, s2: int, s3: int, s4: int, l1: int, l2: int) -> list:
    r0 = op(s1, l1, l2) ^ s4
    r1 = op(r0, l1, l2) ^ s3
    r2 = op(r1, l1, l2) ^ s2
    r3 = op(r2, l1, l2) ^ s1
    r4 = op(r3, l1, l2)
    r5 = op(r4 ^ r0, l1, l2)
    r6 = op(r5 ^ r1, l1, l2)
    r7 = op(r6 ^ r2, l1, l2)
    return [(r0 ^ r4) & 0xffff, (r1 ^ r5) & 0xffff, (r2 ^ r6) & 0xffff, (r3 ^ r7) & 0xffff]

def crypt_fram(sensor_id: bytes, sensor_info: bytes, fram_data: bytes) -> bytes:
    """
    Encrypt or decrypt Libre 2 FRAM data (XOR cipher - same operation for both)
    """
    l1, l2, l3, l4 = 0xa0c5, 0x6860, 0x14c6, 0x0000
    result = bytearray()

    for i in range(43):
        y = word(sensor_info[5], sensor_info[4])
        if i < 3 or i >= 40:
            y = 0xcadc

        if sensor_info[0] == 0xE5 or sensor_info[0] == 0xE6:
            s1 = (word(sensor_id[5], sensor_id[4]) + y + i) & 0xffff
        else:
            s1 = (word(sensor_id[5], sensor_id[4]) + (word(sensor_info[5], sensor_info[4]) ^ 0x44) + i) & 0xffff

        s2 = (word(sensor_id[3], sensor_id[2]) + l4) & 0xffff
        s3 = (word(sensor_id[1], sensor_id[0]) + (i << 1)) & 0xffff
        s4 = (0x241a ^ l3) & 0xffff

        key = process_crypto2(s1, s2, s3, s4, l1, l2)

        base = i * 8
        if base + 7 < len(fram_data):
            result.append(fram_data[base + 0] ^ (key[3] & 0xff))
            result.append(fram_data[base + 1] ^ ((key[3] >> 8) & 0xff))
            result.append(fram_data[base + 2] ^ (key[2] & 0xff))
            result.append(fram_data[base + 3] ^ ((key[2] >> 8) & 0xff))
            result.append(fram_data[base + 4] ^ (key[1] & 0xff))
            result.append(fram_data[base + 5] ^ ((key[1] >> 8) & 0xff))
            result.append(fram_data[base + 6] ^ (key[0] & 0xff))
            result.append(fram_data[base + 7] ^ ((key[0] >> 8) & 0xff))

    return bytes(result)

def crc16(data: bytes) -> int:
    """
    CRC16 calculation matching Libre's algorithm
    """
    def bit_rev(b: int) -> int:
        return (((b << 7) & 0x80) | ((b << 5) & 0x40) | ((b << 3) & 0x20) |
                ((b << 1) & 0x10) | ((b >> 7) & 0x01) | ((b >> 5) & 0x02) |
                ((b >> 3) & 0x04) | ((b >> 1) & 0x08))

    crc = 0xFFFF
    for byte in data:
        crc = ((crc >> 8) & 0xFFFF) | ((crc << 8) & 0xFFFF)
        crc ^= bit_rev(byte)
        crc ^= ((crc & 0xFF) >> 4) & 0xFFFF
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return crc & 0xFFFF

def parse_dump_file(filename: str) -> tuple:
    with open(filename, 'r') as f:
        lines = f.readlines()

    patch_info, uid, memory = None, None, None
    for line in lines:
        line = line.strip()
        if line.startswith('PATCH_INFO:'):
            patch_info = bytes([int(b, 16) for b in line.replace('PATCH_INFO:', '').strip().split()])
        elif line.startswith('UID:'):
            uid = bytes([int(b, 16) for b in line.replace('UID:', '').strip().split()])
        elif line.startswith('MEMORY:'):
            memory = bytes([int(b, 16) for b in line.replace('MEMORY:', '').strip().split()])
    return uid, patch_info, memory

def reset_sensor(uid: bytes, patch_info: bytes, encrypted_memory: bytes,
                 new_age: int = 0, new_status: int = 3) -> bytes:
    """
    Reset sensor age and status

    Args:
        uid: 6 bytes sensor UID
        patch_info: 6 bytes patch info
        encrypted_memory: Original encrypted FRAM (336-344 bytes)
        new_age: New age in 15-minute intervals (0 = just activated)
        new_status: New status (3 = Active)

    Returns:
        Modified encrypted FRAM ready to write back
    """
    # Pad to 344 bytes if needed
    if len(encrypted_memory) < 344:
        encrypted_memory = encrypted_memory + bytes(344 - len(encrypted_memory))

    # Decrypt
    decrypted = bytearray(crypt_fram(uid, patch_info, encrypted_memory))

    print(f"Original decrypted status (byte 4): {decrypted[4]}")
    print(f"Original decrypted age (bytes 316-317): {decrypted[316] + (decrypted[317] << 8)}")

    # Modify status
    decrypted[4] = new_status

    # Modify age (little-endian)
    decrypted[316] = new_age & 0xFF
    decrypted[317] = (new_age >> 8) & 0xFF

    # Recalculate CRC for bytes 26-319 (294 bytes)
    new_crc = crc16(decrypted[26:320])
    decrypted[24] = new_crc & 0xFF
    decrypted[25] = (new_crc >> 8) & 0xFF

    print(f"\nModified status: {decrypted[4]}")
    print(f"Modified age: {decrypted[316] + (decrypted[317] << 8)}")
    print(f"New CRC: {new_crc:04X}")

    # Re-encrypt (XOR cipher - same operation)
    re_encrypted = crypt_fram(uid, patch_info, bytes(decrypted))

    # Return only the original length
    return re_encrypted[:len(encrypted_memory) if len(encrypted_memory) < 344 else 344]

def format_for_app(data: bytes) -> str:
    """Format bytes as hex string for the Android app"""
    return ' '.join(f'{b:02X}' for b in data)

if __name__ == "__main__":
    import sys

    # Process the expired sensor
    dump_file = "/Users/renatoselenica/Projects/OpenLibreNFC/research/libre2_expired_full.txt"

    print("=" * 60)
    print("LIBRE 2 SENSOR AGE RESET")
    print("=" * 60)

    uid, patch_info, memory = parse_dump_file(dump_file)

    print(f"\nSensor UID: {format_for_app(uid)}")
    print(f"Patch Info: {format_for_app(patch_info)}")
    print(f"Original memory size: {len(memory)} bytes")

    # Reset the sensor
    print("\n--- Resetting sensor ---")
    reset_memory = reset_sensor(uid, patch_info, memory, new_age=96, new_status=3)
    # new_age=96 = 1 day (so it looks like a 1-day-old active sensor)

    # Save the modified memory
    output_file = "/Users/renatoselenica/Projects/OpenLibreNFC/research/libre2_reset_memory.txt"
    with open(output_file, 'w') as f:
        f.write(format_for_app(reset_memory))

    print(f"\n--- Reset memory saved to {output_file} ---")
    print(f"Memory size: {len(reset_memory)} bytes")

    # Verify by decrypting the reset memory
    print("\n--- Verification (decrypting reset memory) ---")
    verify_decrypted = crypt_fram(uid, patch_info, reset_memory + bytes(344 - len(reset_memory)))
    print(f"Verified status: {verify_decrypted[4]}")
    print(f"Verified age: {verify_decrypted[316] + (verify_decrypted[317] << 8)} intervals")

    print("\n" + "=" * 60)
    print("Next step: Load this memory back to the sensor using the app")
    print("=" * 60)
