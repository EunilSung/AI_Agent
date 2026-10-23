"""app.py 의 POST /v1/summarize 엔드포인트. Ollama 호출(complete · stream_completion)은 대역으로 바꿔 끼운다.

app.py 는 임시 벡터 DB 로 불러온다(conftest 의 app_module) — 실행 중인 서버의 chroma_data 는 건드리지 않는다.
"""

import urllib.parse

import pytest
from fastapi.testclient import TestClient

from conftest import parse_sse


@pytest.fixture
def client(app_module):
    return TestClient(app_module.app)


@pytest.fixture
def fake_ollama(app_module, monkeypatch):
    calls = []

    async def complete(system, user, max_tokens, temperature=0.3, timeout=120):
        calls.append(("complete", system, user, temperature, timeout))
        return "- 조각 메모"

    async def stream_completion(system, user, max_tokens, temperature=0.3, timeout=300):
        calls.append(("stream", system, user, temperature, timeout))
        for part in ("회의 일정이 바뀌었다.", "\n\n### 주요 내용\n- 출시일 11월 5일"):
            yield part

    monkeypatch.setattr(app_module, "complete", complete)
    monkeypatch.setattr(app_module, "stream_completion", stream_completion)
    return calls


def post(client, name: str, data: bytes):
    """브릿지처럼 파일 이름을 X-File-Name 헤더에 퍼센트 인코딩(공백 %20, + 는 %2B)해 보낸다."""
    return client.post("/v1/summarize", content=data,
                       headers={"Content-Type": "application/octet-stream",
                                "X-File-Name": urllib.parse.quote(name, safe="-._~")})


def test_short_document(client, fake_ollama, app_module):
    res = post(client, "주간 회의록.txt", "출시일을 11월 5일로 미룬다.".encode())
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/event-stream")
    assert res.headers["cache-control"] == "no-cache"
    assert res.text.startswith(": connected\n\n")
    events = parse_sse(res.text)
    assert events == [
        ("comment", "connected"),
        ("delta", {"delta": "회의 일정이 바뀌었다."}),
        ("delta", {"delta": "\n\n### 주요 내용\n- 출시일 11월 5일"}),
        ("done", {}),
    ]
    (kind, system, user, temperature, timeout), = fake_ollama
    assert kind == "stream"
    assert "### 주요 내용" in system and "### 핵심 수치·일정" in system
    assert "[파일 이름] 주간 회의록.txt" in user and "출시일을 11월 5일로 미룬다." in user
    assert temperature == app_module.SUMMARY_TEMPERATURE
    assert timeout == app_module.SUMMARY_TIMEOUT


def test_long_document_progress(client, fake_ollama, app_module):
    text = "\n\n".join(f"{i}번 문단 " + "가" * 900 for i in range(16))
    events = parse_sse(post(client, "보고서.md", text.encode()).text)
    progress = [data for kind, data in events if kind == "progress"]
    assert progress == [{"done": i, "total": 3} for i in range(4)]
    assert [kind for kind, _ in events][-3:] == ["delta", "delta", "done"]
    assert [c[0] for c in fake_ollama] == ["complete", "complete", "complete", "stream"]
    assert all(c[4] == app_module.SUMMARY_TIMEOUT for c in fake_ollama)
    assert "[위치] 전체 3개 조각 중 2번째" in fake_ollama[1][2]
    assert "[메모 3]" in fake_ollama[-1][2]
    assert app_module.SUMMARY_TRUNCATED_NOTE not in fake_ollama[-1][1]


def test_failure_codes(client, fake_ollama):
    events = parse_sse(post(client, "옛문서.doc", b"\xd0\xcf\x11\xe0").text)
    assert events == [
        ("comment", "connected"),
        ("error", {"message": "unsupported file type: doc", "code": "unsupported_file"}),
        ("done", {}),
    ]
    assert fake_ollama == []


def test_too_large_body(client, fake_ollama, monkeypatch):
    import summarize

    monkeypatch.setattr(summarize, "MAX_FILE_BYTES", 10)
    events = parse_sse(post(client, "큰파일.txt", b"x" * 100).text)
    assert [kind for kind, _ in events] == ["comment", "error", "done"]
    assert events[1][1]["code"] == ""


def test_name_is_required(client, fake_ollama):
    assert client.post("/v1/summarize", content=b"abc").status_code == 422
    assert fake_ollama == []


def test_name_comes_from_the_header_with_special_characters(client, fake_ollama):
    events = parse_sse(post(client, "회의록 Q&A + 결과#1.txt", "출시일을 11월 5일로 미룬다.".encode()).text)
    assert [kind for kind, _ in events] == ["comment", "delta", "delta", "done"]
    assert "[파일 이름] 회의록 Q&A + 결과#1.txt\n" in fake_ollama[0][2]


def test_name_in_the_url_is_not_accepted(client, fake_ollama):
    """요청 줄(경로·쿼리)은 uvicorn 접근 로그에 남는다. 파일 이름을 URL 로 받는 길이 없어야 로그에 남지 않는다."""
    res = client.post(f"/v1/summarize?name={urllib.parse.quote('사내공지.hwpx')}", content=b"abc",
                      headers={"Content-Type": "application/octet-stream"})
    assert res.status_code == 422
    assert fake_ollama == []


@pytest.mark.parametrize("value", ["%ED%9A", "%20"])
def test_badly_encoded_name_is_rejected(client, fake_ollama, value):
    res = client.post("/v1/summarize", content=b"abc",
                      headers={"Content-Type": "application/octet-stream", "X-File-Name": value})
    assert res.status_code == 422
    assert fake_ollama == []


def test_health_still_works(client):
    body = client.get("/v1/health").json()
    assert body["status"] == "ok"
    assert body["chunks"] == 0


def test_prompts(app_module):
    system, user = app_module.summary_final("보고서.docx", ["메모 하나", "메모 둘"], truncated=True)
    assert app_module.SUMMARY_TRUNCATED_NOTE in system
    assert "[메모 1]\n메모 하나" in user and "[메모 2]\n메모 둘" in user
    system, _ = app_module.summary_final("보고서.docx", ["메모"], truncated=False)
    assert app_module.SUMMARY_TRUNCATED_NOTE not in system
    system, user = app_module.summary_whole("a.txt", "본문")
    assert "{" not in system and "[문서]" in system and "본문" in user
    system, user = app_module.summary_piece("a.txt", 2, 5, "조각 글")
    assert "[위치] 전체 5개 조각 중 2번째" in user and "조각 글" in user
    system, user = app_module.summary_condense("a.txt", ["가", "나"])
    assert "[메모 1]\n가" in user and "[메모 2]\n나" in user
