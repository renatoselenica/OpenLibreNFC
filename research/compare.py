#!/usr/bin/env python3
"""Compare two Libre 2 memory dumps to find patterns"""

def parse_dump(filename):
    with open(filename, 'r') as f:
        data = f.read().strip()
    return [int(b, 16) for b in data.split()]

expired = parse_dump('/Users/renatoselenica/Projects/OpenLibreNFC/research/libre2_active.txt')
warmup = parse_dump('/Users/renatoselenica/Projects/OpenLibreNFC/research/libre2_warmup.txt')

print(f"Expired sensor: {len(expired)} bytes")
print(f"Warmup sensor: {len(warmup)} bytes")
print()

# Find bytes that are the same
same_positions = []
diff_positions = []
for i in range(min(len(expired), len(warmup))):
    if expired[i] == warmup[i]:
        same_positions.append(i)
    else:
        diff_positions.append(i)

print(f"Identical bytes at {len(same_positions)} positions: {same_positions}")
print()

# Show first 32 bytes side by side
print("First 32 bytes comparison:")
print("Pos  | Expired | Warmup  | Same?")
print("-----|---------|---------|------")
for i in range(32):
    same = "YES" if expired[i] == warmup[i] else ""
    print(f"{i:3d}  |   {expired[i]:02X}    |   {warmup[i]:02X}    | {same}")

print()
print("Byte 0 (both sensors):", hex(expired[0]), hex(warmup[0]))
print("Byte 4 (Libre 1 status location):", hex(expired[4]), hex(warmup[4]))
