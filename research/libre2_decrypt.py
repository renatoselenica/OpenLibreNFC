#!/usr/bin/env python3
"""
Libre 2 FRAM Decryption Algorithm
Ported from xDripSwift PreLibre2.swift
Original: https://github.com/JohanDegraeve/xdripswift
"""

def word(high: int, low: int) -> int:
    """Combine two bytes into a 16-bit word"""
    return ((high & 0xff) << 8) + (low & 0xff)

def op(value: int, l1: int, l2: int) -> int:
    """Bit manipulation operation"""
    res = value >> 2
    if value & 1:
        res ^= l2
    if value & 2:
        res ^= l1
    return res & 0xffff

def process_crypto2(s1: int, s2: int, s3: int, s4: int, l1: int, l2: int) -> list:
    """Generate keystream for decryption"""
    r0 = op(s1, l1, l2) ^ s4
    r1 = op(r0, l1, l2) ^ s3
    r2 = op(r1, l1, l2) ^ s2
    r3 = op(r2, l1, l2) ^ s1
    r4 = op(r3, l1, l2)
    r5 = op(r4 ^ r0, l1, l2)
    r6 = op(r5 ^ r1, l1, l2)
    r7 = op(r6 ^ r2, l1, l2)

    f1 = (r0 ^ r4) & 0xffff
    f2 = (r1 ^ r5) & 0xffff
    f3 = (r2 ^ r6) & 0xffff
    f4 = (r3 ^ r7) & 0xffff

    return [f1, f2, f3, f4]

def decrypt_fram(sensor_id: bytes, sensor_info: bytes, fram_data: bytes) -> bytes:
    """
    Decrypt Libre 2 FRAM data to Libre 1 format

    Args:
        sensor_id: 6 bytes UID
        sensor_info: 6 bytes patch info
        fram_data: 344 bytes encrypted FRAM

    Returns:
        344 bytes decrypted data (Libre 1 format)
    """
    l1 = 0xa0c5
    l2 = 0x6860
    l3 = 0x14c6
    l4 = 0x0000

    result = bytearray()

    for i in range(43):
        # Calculate y based on sensor info
        y = word(sensor_info[5], sensor_info[4])
        if i < 3 or i >= 40:
            y = 0xcadc

        # Calculate s1 based on sensor type
        if sensor_info[0] == 0xE5 or sensor_info[0] == 0xE6:
            ss1 = word(sensor_id[5], sensor_id[4]) + y + i
            s1 = ss1 & 0xffff
        else:
            ss1 = word(sensor_id[5], sensor_id[4]) + (word(sensor_info[5], sensor_info[4]) ^ 0x44) + i
            s1 = ss1 & 0xffff

        s2 = (word(sensor_id[3], sensor_id[2]) + l4) & 0xffff
        s3 = (word(sensor_id[1], sensor_id[0]) + (i << 1)) & 0xffff
        s4 = (0x241a ^ l3) & 0xffff

        # Generate key for this block
        key = process_crypto2(s1, s2, s3, s4, l1, l2)

        # XOR decrypt the 8-byte block
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

    return bytes(result[:344])

def parse_dump_file(filename: str) -> tuple:
    """Parse our dump file format"""
    with open(filename, 'r') as f:
        lines = f.readlines()

    patch_info = None
    uid = None
    memory = None

    for line in lines:
        line = line.strip()
        if line.startswith('PATCH_INFO:'):
            hex_str = line.replace('PATCH_INFO:', '').strip()
            patch_info = bytes([int(b, 16) for b in hex_str.split()])
        elif line.startswith('UID:'):
            hex_str = line.replace('UID:', '').strip()
            uid = bytes([int(b, 16) for b in hex_str.split()])
        elif line.startswith('MEMORY:'):
            hex_str = line.replace('MEMORY:', '').strip()
            memory = bytes([int(b, 16) for b in hex_str.split()])

    return uid, patch_info, memory

def analyze_decrypted_data(data: bytes, label: str):
    """Analyze decrypted Libre 1 format data"""
    print(f"\n=== {label} ===")
    print(f"Total bytes: {len(data)}")

    if len(data) < 320:
        print("Not enough data for full analysis")
        return

    # Libre 1 memory structure (now decrypted)
    print(f"\nHeader bytes (0-3): {data[0]:02X} {data[1]:02X} {data[2]:02X} {data[3]:02X}")

    status = data[4]
    status_map = {1: "New", 2: "Warming up", 3: "Active", 5: "Expired", 6: "Error"}
    print(f"Status (byte 4): {status} = {status_map.get(status, 'Unknown')}")

    # Age is at bytes 316-317 (little endian)
    if len(data) > 317:
        age = data[316] + (data[317] << 8)
        age_days = age / 96  # 15-minute intervals, 96 per day
        print(f"Age (bytes 316-317): {age} intervals = {age_days:.2f} days")

    # Region at byte 323
    if len(data) > 323:
        region = data[323]
        print(f"Region (byte 323): {region}")

    # CRC at bytes 24-25
    crc = data[24] + (data[25] << 8)
    print(f"CRC (bytes 24-25): {crc:04X}")

    # Trend index at byte 26
    trend_idx = data[26]
    print(f"Trend index (byte 26): {trend_idx}")

    # Print first 64 bytes of decrypted data
    print(f"\nFirst 64 bytes (decrypted):")
    for i in range(0, 64, 16):
        hex_line = ' '.join(f'{data[i+j]:02X}' for j in range(min(16, len(data)-i)))
        print(f"  {i:3d}: {hex_line}")

if __name__ == "__main__":
    import sys

    # Process our sensor dumps
    dumps = [
        ("/Users/renatoselenica/Projects/OpenLibreNFC/research/libre2_expired_full.txt", "EXPIRED SENSOR"),
        ("/Users/renatoselenica/Projects/OpenLibreNFC/research/libre2_warmup_full.txt", "WARMING UP SENSOR"),
    ]

    for dump_file, label in dumps:
        try:
            uid, patch_info, memory = parse_dump_file(dump_file)

            print(f"\n{'='*60}")
            print(f"{label}")
            print(f"{'='*60}")
            print(f"UID: {' '.join(f'{b:02X}' for b in uid)}")
            print(f"Patch Info: {' '.join(f'{b:02X}' for b in patch_info)}")
            print(f"Memory size: {len(memory)} bytes")

            # Pad memory to 344 bytes if needed
            if len(memory) < 344:
                print(f"Warning: Memory is {len(memory)} bytes, padding to 344")
                memory = memory + bytes(344 - len(memory))

            decrypted = decrypt_fram(uid, patch_info, memory)
            analyze_decrypted_data(decrypted, f"{label} - DECRYPTED")

        except Exception as e:
            print(f"Error processing {dump_file}: {e}")
