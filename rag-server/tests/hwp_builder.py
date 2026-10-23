"""시험용 HWP 5.0 파일을 만든다 — 바이너리 파일을 저장소에 넣지 않으려고 테스트 안에서 직접 짓는다.

HWP 5.0 은 OLE(복합 문서) 안에 FileHeader · BodyText/Section{n} 스트림을 둔다. olefile 은 읽기만 하므로
OLE 를 쓰는 부분(512바이트 섹터, FAT, 4096바이트보다 작은 스트림을 담는 미니 스트림)도 여기서 만든다.
"""

import math
import struct
import zlib

SECTOR = 512
MINI_SECTOR = 64
MINI_CUTOFF = 4096
FREESECT = 0xFFFFFFFF
ENDOFCHAIN = 0xFFFFFFFE
FATSECT = 0xFFFFFFFD
NOSTREAM = 0xFFFFFFFF
OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"

HWPTAG_PARA_HEADER = 66
HWPTAG_PARA_TEXT = 67
HWPTAG_CTRL_HEADER = 71


def build_ole(streams: dict[str, bytes]) -> bytes:
    """{"Storage/Stream": 바이트} 로 OLE 파일을 만든다. 한 단계 저장소(폴더)까지 지원한다."""
    tree: dict = {}
    for path, data in streams.items():
        node = tree
        *folders, leaf = path.split("/")
        for folder in folders:
            node = node.setdefault(folder, {})
        node[leaf] = data

    entries: list[dict] = []

    def add(name, kind, data=None):
        entries.append({"name": name, "type": kind, "data": data, "left": NOSTREAM, "right": NOSTREAM,
                        "child": NOSTREAM, "start": ENDOFCHAIN, "size": 0})
        return len(entries) - 1

    def build(node, parent):
        ids = []
        for name, value in node.items():
            if isinstance(value, dict):
                index = add(name, 1)
                build(value, index)
            else:
                index = add(name, 2, value)
            ids.append(index)
        if ids:
            entries[parent]["child"] = ids[0]
            for a, b in zip(ids, ids[1:]):
                entries[a]["right"] = b

    root = add("Root Entry", 5)
    build(tree, root)

    mini_data, mini_fat, big = bytearray(), [], []
    for index, entry in enumerate(entries):
        if entry["type"] != 2:
            continue
        data = entry["data"]
        entry["size"] = len(data)
        if not data:
            continue
        if len(data) < MINI_CUTOFF:
            first = len(mini_data) // MINI_SECTOR
            count = math.ceil(len(data) / MINI_SECTOR)
            entry["start"] = first
            mini_fat.extend(range(first + 1, first + count))
            mini_fat.append(ENDOFCHAIN)
            mini_data += data + b"\0" * (-len(data) % MINI_SECTOR)
        else:
            big.append(index)

    dir_count = math.ceil(len(entries) * 128 / SECTOR)
    minifat_count = math.ceil(len(mini_fat) * 4 / SECTOR)
    ministream_count = math.ceil(len(mini_data) / SECTOR)
    big_counts = [math.ceil(entries[i]["size"] / SECTOR) for i in big]
    others = dir_count + minifat_count + ministream_count + sum(big_counts)
    fat_count = 1
    while fat_count * (SECTOR // 4) < others + fat_count:
        fat_count += 1
    assert fat_count <= 109

    fat = [FREESECT] * (fat_count * SECTOR // 4)
    for i in range(fat_count):
        fat[i] = FATSECT
    cursor = fat_count

    def chain(count):
        nonlocal cursor
        if count == 0:
            return ENDOFCHAIN
        first = cursor
        for i in range(first, first + count - 1):
            fat[i] = i + 1
        fat[first + count - 1] = ENDOFCHAIN
        cursor += count
        return first

    dir_start = chain(dir_count)
    minifat_start = chain(minifat_count)
    ministream_start = chain(ministream_count)
    entries[root]["start"] = ministream_start
    entries[root]["size"] = len(mini_data)
    for index, count in zip(big, big_counts):
        entries[index]["start"] = chain(count)

    directory = bytearray()
    for entry in entries:
        name = (entry["name"] + "\0").encode("utf-16-le")
        directory += name.ljust(64, b"\0")
        directory += struct.pack("<HBBIII", len(name), entry["type"], 1, entry["left"], entry["right"], entry["child"])
        directory += b"\0" * 16 + struct.pack("<I", 0) + b"\0" * 16
        directory += struct.pack("<IQ", entry["start"], entry["size"])
    while len(directory) % SECTOR:
        directory += b"\0" * 64 + struct.pack("<HBBIII", 0, 0, 0, NOSTREAM, NOSTREAM, NOSTREAM) + b"\0" * 48

    header = struct.pack(
        "<8s16sHHHHH6sIIIIIIIII", OLE_MAGIC, b"\0" * 16, 0x3E, 0x3, 0xFFFE, 9, 6, b"\0" * 6,
        0, fat_count, dir_start, 0, MINI_CUTOFF, minifat_start, minifat_count, ENDOFCHAIN, 0,
    )
    header += struct.pack("<109I", *(list(range(fat_count)) + [FREESECT] * (109 - fat_count)))

    def pad(data: bytes) -> bytes:
        return bytes(data) + b"\0" * (-len(data) % SECTOR)

    body = struct.pack(f"<{len(fat)}I", *fat)
    body += pad(directory)
    body += pad(struct.pack(f"<{len(mini_fat)}I", *mini_fat)) if mini_fat else b""
    body += pad(mini_data)
    for index in big:
        body += pad(entries[index]["data"])
    return header + body


def record(tag: int, payload: bytes, level: int = 0) -> bytes:
    """HWP 레코드: 헤더(tag 10bit · level 10bit · size 12bit) + 내용. 4095바이트 이상이면 크기를 뒤 4바이트에 둔다."""
    if len(payload) >= 0xFFF:
        return struct.pack("<II", tag | (level << 10) | (0xFFF << 20), len(payload)) + payload
    return struct.pack("<I", tag | (level << 10) | (len(payload) << 20)) + payload


def para_text(text: str) -> bytes:
    """문단 글 레코드 내용. 끝에 문단 끝(13)을 붙인다."""
    return (text + "\r").encode("utf-16-le")


def extended_control(code: int = 11, ctrl_id: bytes = b" lbt") -> bytes:
    """표·그림 자리 같은 확장 제어 문자 — 8글자(16바이트)를 차지한다."""
    return struct.pack("<H", code) + ctrl_id + b"\0" * 8 + struct.pack("<H", code)


def paragraph(text: str, level: int = 0, prefix: bytes = b"") -> bytes:
    return record(HWPTAG_PARA_HEADER, b"\0" * 22, level) + record(HWPTAG_PARA_TEXT, prefix + para_text(text), level + 1)


def build_hwp(sections: list[list[bytes]], compressed: bool = True, flags: int = 0) -> bytes:
    """sections: 구역마다 레코드 바이트 목록. flags 는 FileHeader 속성(bit1 암호, bit2 배포용)."""
    properties = flags | (1 if compressed else 0)
    header = b"HWP Document File".ljust(32, b"\0") + struct.pack("<II", 0x05000300, properties)
    header = header.ljust(256, b"\0")
    streams = {"FileHeader": header, "DocInfo": b"\0" * 32}
    for number, records in enumerate(sections):
        raw = b"".join(records)
        if compressed:
            packer = zlib.compressobj(9, zlib.DEFLATED, -15)
            raw = packer.compress(raw) + packer.flush()
        streams[f"BodyText/Section{number}"] = raw
    return build_ole(streams)
