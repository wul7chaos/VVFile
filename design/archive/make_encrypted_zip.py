"""生成一个 ZipCrypto 加密的 zip，用于验证 VVFile 对带密码压缩包的处理。

只为实现验证样本，不参与 App 构建。
"""

import binascii
import os
import struct
import sys

CRC_TABLE = []
for i in range(256):
    c = i
    for _ in range(8):
        c = (c >> 1) ^ 0xEDB88320 if c & 1 else c >> 1
    CRC_TABLE.append(c)


def crc32_byte(ch, crc):
    return (crc >> 8) ^ CRC_TABLE[(crc ^ ch) & 0xFF]


def crc32(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc = (crc >> 8) ^ CRC_TABLE[(crc ^ byte) & 0xFF]
    return crc ^ 0xFFFFFFFF


class ZipCrypto:
    def __init__(self, password: bytes):
        self.k = [0x12345678, 0x23456789, 0x34567890]
        for byte in password:
            self.update(byte)

    def update(self, byte):
        self.k[0] = crc32_byte(byte, self.k[0])
        self.k[1] = (self.k[1] + (self.k[0] & 0xFF)) & 0xFFFFFFFF
        self.k[1] = (self.k[1] * 134775813 + 1) & 0xFFFFFFFF
        self.k[2] = crc32_byte((self.k[1] >> 24) & 0xFF, self.k[2])

    def encrypt(self, byte):
        temp = (self.k[2] | 2) & 0xFFFF
        cipher = (byte ^ ((temp * (temp ^ 1)) >> 8)) & 0xFF
        self.update(byte)
        return cipher


def build_zip(entries, password: bytes, out_path: str):
    local_parts = []
    central_parts = []
    offset = 0
    dos_time = 0
    dos_date = (2026 - 1980) << 9 | (10 << 5) | 27

    for name, data in entries:
        name_bytes = name.encode("utf-8")
        crc = crc32(data)
        encrypted = bytes([ZipCrypto(password).encrypt(b) for b in b""]) if False else None
        cipher = ZipCrypto(password)
        header12 = os.urandom(11) + bytes([(crc >> 24) & 0xFF])
        body = bytes([cipher.encrypt(b) for b in header12 + data])
        flags = 0x0001  # bit0 = encrypted
        method = 0  # stored

        local = struct.pack(
            "<IHHHHHIIIHH",
            0x04034B50, 20, flags, method, dos_time, dos_date,
            crc, len(body), len(data), len(name_bytes), 0,
        ) + name_bytes + body
        central = struct.pack(
            "<IHHHHHHIIIHHHHHII",
            0x02014B50, 20, 20, flags, method, dos_time, dos_date,
            crc, len(body), len(data), len(name_bytes), 0, 0, 0, 0, 0, offset,
        ) + name_bytes

        local_parts.append(local)
        central_parts.append(central)
        offset += len(local)

    central_blob = b"".join(central_parts)
    eocd = struct.pack(
        "<IHHHHIIH",
        0x06054B50, 0, 0, len(entries), len(entries),
        len(central_blob), offset, 0,
    )
    with open(out_path, "wb") as fh:
        fh.write(b"".join(local_parts))
        fh.write(central_blob)
        fh.write(eocd)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "encrypted.zip"
    build_zip(
        [
            ("secret.txt", "密码是 123456 的内容\n".encode("utf-8")),
            ("docs/note.md", "# 加密包里的笔记\n".encode("utf-8")),
        ],
        b"123456",
        out,
    )
    print("written", out, os.path.getsize(out), "bytes")
