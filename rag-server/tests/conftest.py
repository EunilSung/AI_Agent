"""테스트 공통. rag-server 폴더와 tests 폴더를 import 경로에 넣고, app.py 는 임시 벡터 DB 로 불러온다."""

import os
import sys
from pathlib import Path

import pytest

TESTS_DIR = Path(__file__).resolve().parent
sys.path[:0] = [str(TESTS_DIR.parent), str(TESTS_DIR)]


@pytest.fixture(scope="session")
def app_module(tmp_path_factory):
    """app.py 를 불러온다. 실행 중인 서버의 chroma_data 를 건드리지 않게 CHROMA_DIR 을 임시 폴더로 바꾼다."""
    chroma_dir = str(tmp_path_factory.mktemp("chroma"))
    os.environ["CHROMA_DIR"] = chroma_dir
    os.environ.setdefault("ANONYMIZED_TELEMETRY", "False")
    import app

    assert app.CHROMA_DIR == chroma_dir, "app.py 가 임시 DB 가 아닌 곳을 열었다"
    return app


def parse_sse(body: str) -> list[tuple[str, object]]:
    """SSE 글을 [(종류, 값)] 으로. 주석 줄은 ("comment", 글), 이벤트는 (이벤트 이름 또는 "delta", data JSON)."""
    import json

    out = []
    for block in body.split("\n\n"):
        if not block:
            continue
        event, data = None, None
        for line in block.split("\n"):
            if line.startswith(":"):
                out.append(("comment", line[1:].strip()))
            elif line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                data = json.loads(line[6:])
        if data is not None:
            out.append((event or "delta", data))
    return out
