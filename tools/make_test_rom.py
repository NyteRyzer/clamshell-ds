#!/usr/bin/env python3
"""Builds a tiny DS ROM for smoke tests: the ARM9 loops writing to VRAM and main RAM
(exercising the JIT and its fast-memory fault path), the ARM7 idles."""
import struct, sys

rom = bytearray(0x20000)
rom[0:12] = b"CLAMSHELLTST"
rom[0x0C:0x10] = b"CLAM"
struct.pack_into("<IIII", rom, 0x20, 0x8000, 0x02000000, 0x02000000, 0x100)   # ARM9
struct.pack_into("<IIII", rom, 0x30, 0xC000, 0x02380000, 0x02380000, 0x100)   # ARM7
struct.pack_into("<I", rom, 0x80, len(rom))
arm9 = [
    0xE3A0051A,  # mov r0, #0x06800000   (LCDC VRAM)
    0xE3A03402,  # mov r3, #0x02000000   (main RAM)
    0xE2833601,  # add r3, r3, #0x100000
    0xE5801000,  # loop: str r1, [r0]
    0xE5831000,  #       str r1, [r3]
    0xE5932000,  #       ldr r2, [r3]
    0xE2811001,  #       add r1, r1, #1
    0xEAFFFFFA,  #       b loop
]
for i, ins in enumerate(arm9):
    struct.pack_into("<I", rom, 0x8000 + i * 4, ins)
struct.pack_into("<I", rom, 0xC000, 0xEAFFFFFE)  # ARM7: b .
open(sys.argv[1], "wb").write(rom)
