"""summarize.py: 글 다듬기·나누기와 요약 SSE 흐름. LLM 은 대역으로 바꿔 끼운다."""

import asyncio
import threading
import time

import pytest

import extract
import summarize
from conftest import parse_sse
from summarize import Llm, SummaryPrompts, split_pieces, summary_stream, tidy, with_pings

PROMPTS = SummaryPrompts(
    whole=lambda name, text: ("WHOLE", f"{name}|{text}"),
    piece=lambda name, number, total, piece: ("PIECE", f"{number}/{total}|{piece}"),
    condense=lambda name, notes: ("CONDENSE", "|".join(notes)),
    final=lambda name, notes, truncated: ("FINAL", f"truncated={truncated}|" + "|".join(notes)),
)


class FakeLlm:
    """부른 기록을 남기는 대역 LLM. note 는 조각·묶음 메모를, answer 는 스트리밍할 글 조각을 정한다."""

    def __init__(self, answer=("요약 ", "첫 줄"), note=lambda system, user: f"메모({user.split('|')[0]})",
                 delay: float = 0, fail_after: int | None = None):
        self.answer = answer
        self.note = note
        self.delay = delay
        self.fail_after = fail_after
        self.calls: list[tuple[str, str, str, int]] = []
        self.stream_closed = False

    async def complete(self, system: str, user: str, max_tokens: int) -> str:
        self.calls.append(("complete", system, user, max_tokens))
        if self.delay:
            await asyncio.sleep(self.delay)
        return self.note(system, user)

    async def stream(self, system: str, user: str, max_tokens: int):
        self.calls.append(("stream", system, user, max_tokens))
        try:
            for index, part in enumerate(self.answer):
                if self.fail_after is not None and index == self.fail_after:
                    raise RuntimeError("ollama went away")
                if self.delay:
                    await asyncio.sleep(self.delay)
                yield part
        finally:
            self.stream_closed = True

    @property
    def llm(self) -> Llm:
        return Llm(complete=self.complete, stream=self.stream)


def run(name: str, data: bytes | None, fake: FakeLlm, ping_seconds: float = 10) -> list[tuple[str, object]]:
    async def collect():
        return "".join([item async for item in summary_stream(name, data, fake.llm, PROMPTS, ping_seconds)])

    return parse_sse(asyncio.run(collect()))


def kinds(events) -> list[str]:
    return [kind for kind, _ in events]


def long_text(paragraphs: int, size: int = 900) -> str:
    return "\n\n".join(f"{i}번 문단 " + "가" * size for i in range(paragraphs))


# ---------- 다듬기 · 나누기 ----------

def test_tidy():
    raw = "﻿첫  줄\t\t끝  \r\n\r\n\r\n\r\n둘째​ 줄\x00\x07\r셋째　줄 \n"
    assert tidy(raw) == "첫 줄 끝\n\n둘째 줄\n셋째 줄"


@pytest.mark.parametrize("value, name", [
    ("%ED%9A%8C%EC%9D%98%EB%A1%9D%20Q%26A%20%2B%20%EA%B2%B0%EA%B3%BC%231.pdf", "회의록 Q&A + 결과#1.pdf"),
    ("report.txt", "report.txt"),
    ("a+b.txt", "a+b.txt"),
    ("100%25.txt", "100%.txt"),
])
def test_decode_name_header(value, name):
    assert summarize.decode_name_header(value) == name


@pytest.mark.parametrize("value", [
    "",
    "%20%20",
    "%ED%9A",
    "%FF.txt",
    "회의록.txt",
])
def test_decode_name_header_rejects_bad_values(value):
    assert summarize.decode_name_header(value) is None


def test_clean_name():
    assert summarize.clean_name("../../etc/보고서\x00.docx") == "보고서.docx"
    assert summarize.clean_name("a\\b\\c.txt") == "c.txt"
    assert len(summarize.clean_name("가" * 400 + ".txt")) == summarize.MAX_NAME_CHARS


def test_split_keeps_every_character_and_size():
    text = long_text(40)
    pieces = split_pieces(text, 7000)
    assert all(len(p) <= 7000 for p in pieces)
    assert "".join(pieces).replace("\n", "") == text.replace("\n", "")
    assert len(pieces) == 6


def test_split_prefers_paragraph_boundaries():
    text = long_text(20)
    for piece in split_pieces(text, 4000):
        assert piece.split(" ")[0].endswith("번"), piece[:20]


def test_split_balances_pieces():
    pieces = split_pieces(long_text(16), 7000)
    lengths = [len(p) for p in pieces]
    assert len(pieces) == 3
    assert min(lengths) > max(lengths) * 0.6


def test_split_cuts_long_lines_at_sentence_ends():
    line = "이 문장은 깁니다. " * 1000
    pieces = split_pieces(line.strip(), 3000)
    assert all(len(p) <= 3000 for p in pieces)
    assert all(p.endswith("깁니다.") for p in pieces)
    no_spaces = "가" * 7500
    assert [len(p) for p in split_pieces(no_spaces, 3000)] == [3000, 3000, 1500]


def test_split_lines_without_blank_lines():
    text = "\n".join(f"행 {i} | " + "나" * 50 for i in range(500))
    pieces = split_pieces(text, 7000)
    assert all(len(p) <= 7000 for p in pieces)
    assert all(p.startswith("행 ") for p in pieces)
    assert sum(p.count("\n") + 1 for p in pieces) == 500


def test_split_short_and_empty():
    assert split_pieces("짧은 글", 7000) == ["짧은 글"]
    assert split_pieces("  \n ", 7000) == []


def test_note_groups_keep_order_and_balance():
    notes = [f"메모{i}" + "다" * 1000 for i in range(12)]
    groups = summarize.note_groups(notes, 5000)
    assert [n for g in groups for n in g] == notes
    assert len(groups) == 3
    assert all(len(g) == 4 for g in groups)


# ---------- SSE 흐름 ----------

def test_short_document_streams_in_one_pass():
    fake = FakeLlm()
    events = run("회의록.txt", "회의 내용입니다.".encode(), fake)
    assert events == [
        ("comment", "connected"),
        ("delta", {"delta": "요약 "}),
        ("delta", {"delta": "첫 줄"}),
        ("done", {}),
    ]
    assert [c[:3] for c in fake.calls] == [("stream", "WHOLE", "회의록.txt|회의 내용입니다.")]
    assert fake.calls[0][3] == summarize.SUMMARY_MAX_TOKENS
    assert fake.stream_closed


def test_long_document_reports_progress_then_streams_final_summary():
    fake = FakeLlm()
    text = long_text(16)
    events = run("보고서.txt", text.encode(), fake)
    assert kinds(events) == ["comment", "progress", "progress", "progress", "progress", "delta", "delta", "done"]
    assert [data for kind, data in events if kind == "progress"] == [
        {"done": 0, "total": 3}, {"done": 1, "total": 3}, {"done": 2, "total": 3}, {"done": 3, "total": 3},
    ]
    pieces = [c for c in fake.calls if c[1] == "PIECE"]
    assert [c[2].split("|")[0] for c in pieces] == ["1/3", "2/3", "3/3"]
    assert "".join(c[2].split("|", 1)[1] for c in pieces).replace("\n", "") == tidy(text).replace("\n", "")
    final = fake.calls[-1]
    assert final[:2] == ("stream", "FINAL")
    assert final[2] == "truncated=False|메모(1/3)|메모(2/3)|메모(3/3)"


def test_very_long_document_reads_only_the_front():
    fake = FakeLlm()
    text = long_text(summarize.MAX_PIECES * 9)
    events = run("아주긴.txt", text.encode(), fake)
    progress = [data for kind, data in events if kind == "progress"]
    assert progress[0] == {"done": 0, "total": summarize.MAX_PIECES}
    assert progress[-1] == {"done": summarize.MAX_PIECES, "total": summarize.MAX_PIECES}
    assert len([c for c in fake.calls if c[1] == "PIECE"]) == summarize.MAX_PIECES
    assert fake.calls[-1][2].startswith("truncated=True|")


def test_long_notes_are_condensed_before_final_summary(monkeypatch):
    monkeypatch.setattr(summarize, "NOTES_CHARS", 3000)
    fake = FakeLlm(note=lambda system, user: "긴 메모 " * 200 if system == "PIECE" else "줄인 메모")
    events = run("보고서.txt", long_text(30).encode(), fake)
    assert events[-1] == ("done", {})
    condensed = [c for c in fake.calls if c[1] == "CONDENSE"]
    assert len(condensed) >= 2
    assert fake.calls[-1][2] == "truncated=False|" + "|".join(["줄인 메모"] * len(condensed))


def test_extract_failure_sends_error_code_then_done():
    fake = FakeLlm()
    events = run("암호.pdf", b"not really a pdf", fake)
    assert events == [
        ("comment", "connected"),
        ("error", {"message": "not a PDF file", "code": "broken_file"}),
        ("done", {}),
    ]
    assert fake.calls == []


@pytest.mark.parametrize("name, data, code", [
    ("사진.png", b"\x89PNG", "unsupported_file"),
    ("빈.txt", b"", "no_text"),
    ("공백.txt", b"   \n\n  ", "no_text"),
])
def test_error_codes(name, data, code):
    events = run(name, data, FakeLlm())
    assert events[1][0] == "error" and events[1][1]["code"] == code
    assert events[-1] == ("done", {})


def test_llm_failure_after_partial_answer():
    fake = FakeLlm(answer=("앞부분", "뒷부분"), fail_after=1)
    events = run("회의록.txt", "회의 내용".encode(), fake)
    assert events == [
        ("comment", "connected"),
        ("delta", {"delta": "앞부분"}),
        ("error", {"message": "ollama went away", "code": ""}),
        ("done", {}),
    ]


def test_too_large_body_is_an_error_without_code():
    events = run("큰파일.pdf", None, FakeLlm())
    assert kinds(events) == ["comment", "error", "done"]
    assert events[1][1]["code"] == ""


def test_summary_is_cut_at_max_chars(monkeypatch):
    monkeypatch.setattr(summarize, "MAX_SUMMARY_CHARS", 10)
    fake = FakeLlm(answer=("일이삼사오육", "칠팔구십일이삼", "더 오면 안 됨"))
    events = run("a.txt", "내용".encode(), fake)
    deltas = [data["delta"] for kind, data in events if kind == "delta"]
    assert "".join(deltas) == "일이삼사오육칠팔구십"
    assert events[-1] == ("done", {})
    assert fake.stream_closed


def test_pings_while_waiting_for_slow_steps():
    fake = FakeLlm(delay=0.12)
    events = run("보고서.txt", long_text(16).encode(), fake, ping_seconds=0.05)
    pings = [data for kind, data in events if kind == "comment" and data == "ping"]
    assert len(pings) >= 3
    without_pings = [k for k, d in events if not (k == "comment" and d == "ping")]
    assert without_pings == ["comment", "progress", "progress", "progress", "progress", "delta", "delta", "done"]


def test_no_pings_when_fast():
    events = run("보고서.txt", long_text(16).encode(), FakeLlm(), ping_seconds=5)
    assert ("comment", "ping") not in events


def test_closing_the_stream_cancels_the_running_step():
    state = {"cancelled": False}

    async def slow_source():
        yield "첫 조각"
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            state["cancelled"] = True
            raise
        yield "오지 않음"

    async def scenario():
        stream = with_pings(slow_source(), 0.02)
        assert await anext(stream) == "첫 조각"
        assert await anext(stream) == summarize.PING
        await stream.aclose()
        await asyncio.sleep(0.05)

    asyncio.run(scenario())
    assert state["cancelled"]


# ---------- 글 준비(스레드) ----------

def record_calls(monkeypatch, record):
    """tidy·split_pieces 를 부른 스레드와 받은 글 길이를 record 에 남기게 감싼다."""
    tidy_, split_ = summarize.tidy, summarize.split_pieces

    def tidy_spy(text):
        record.append(("tidy", threading.get_ident(), len(text)))
        return tidy_(text)

    def split_spy(text, size=summarize.PIECE_CHARS):
        record.append(("split", threading.get_ident(), len(text)))
        return split_(text, size)

    monkeypatch.setattr(summarize, "tidy", tidy_spy)
    monkeypatch.setattr(summarize, "split_pieces", split_spy)


def test_tidy_and_split_run_off_the_event_loop(monkeypatch):
    record = []
    record_calls(monkeypatch, record)
    events = run("보고서.txt", long_text(16).encode(), FakeLlm())
    assert events[-1] == ("done", {})
    assert [kind for kind, _, _ in record] == ["tidy", "split"]
    assert threading.get_ident() not in {thread for _, thread, _ in record}


def test_only_the_needed_front_is_split_and_pieces_stay_the_same(monkeypatch):
    text = long_text(200)
    full = tidy(text)
    expected = split_pieces(full, summarize.PIECE_CHARS)[:summarize.MAX_PIECES]
    record = []
    record_calls(monkeypatch, record)
    prepared = summarize.prepare_text("긴 보고서.txt", text.encode())
    keep = (summarize.MAX_PIECES + summarize.KEEP_EXTRA_PIECES) * summarize.PIECE_CHARS
    assert len(full) > keep
    assert record[-1][0] == "split" and record[-1][2] == keep
    assert prepared.pieces == expected
    assert prepared.truncated and prepared.chars == len(full)


def test_truncated_is_judged_by_the_length_before_trimming(monkeypatch):
    monkeypatch.setattr(summarize, "PIECE_CHARS", 100)
    monkeypatch.setattr(summarize, "MAX_PIECES", 3)
    monkeypatch.setattr(summarize, "SHORT_TEXT_CHARS", 50)
    monkeypatch.setattr(summarize, "KEEP_EXTRA_PIECES", 0)
    longer = summarize.prepare_text("a.txt", ("가" * 1000).encode())
    assert [len(p) for p in longer.pieces] == [100, 100, 100]
    assert longer.truncated and longer.chars == 1000
    exact = summarize.prepare_text("a.txt", ("가" * 300).encode())
    assert [len(p) for p in exact.pieces] == [100, 100, 100]
    assert not exact.truncated


def test_text_cut_while_extracting_is_read_in_pieces_and_marked(monkeypatch):
    monkeypatch.setattr(extract, "MAX_TEXT_CHARS", 300)
    fake = FakeLlm()
    events = run("잘린 문서.txt", ("가나다 " * 200).encode(), fake)
    assert [data for kind, data in events if kind == "progress"] == [{"done": 0, "total": 1}, {"done": 1, "total": 1}]
    assert fake.calls[-1][:2] == ("stream", "FINAL")
    assert fake.calls[-1][2].startswith("truncated=True|")


# ---------- 브릿지가 기대하는 순서: connected 먼저, 첫 글자 전까지 ping ----------

def test_connected_is_sent_before_extraction_starts(monkeypatch):
    calls = []

    def prepare(name, data):
        calls.append(name)
        return summarize.Prepared(text="회의 내용", pieces=[], truncated=False, chars=5)

    monkeypatch.setattr(summarize, "prepare_text", prepare)

    async def scenario():
        stream = summary_stream("회의록.txt", b"x", FakeLlm().llm, PROMPTS)
        first = await anext(stream)
        started_before_first = list(calls)
        rest = [item async for item in stream]
        return first, started_before_first, rest

    first, started_before_first, rest = asyncio.run(scenario())
    assert first == ": connected\n\n"
    assert started_before_first == []
    assert calls == ["회의록.txt"]
    assert kinds(parse_sse("".join(rest))) == ["delta", "delta", "done"]


def test_pings_while_extracting(monkeypatch):
    original = summarize.prepare_text

    def slow(name, data):
        time.sleep(0.3)
        return original(name, data)

    monkeypatch.setattr(summarize, "prepare_text", slow)
    events = run("회의록.txt", "회의 내용".encode(), FakeLlm(), ping_seconds=0.05)
    first_delta = kinds(events).index("delta")
    assert events[0] == ("comment", "connected")
    assert events[1:first_delta].count(("comment", "ping")) >= 2


def test_short_document_pings_before_the_first_letter():
    fake = FakeLlm(delay=0.25)
    events = run("회의록.txt", "회의 내용입니다.".encode(), fake, ping_seconds=0.05)
    first_delta = kinds(events).index("delta")
    assert events[0] == ("comment", "connected")
    assert events[1:first_delta].count(("comment", "ping")) >= 2
    assert "progress" not in kinds(events)
    assert [c[0] for c in fake.calls] == ["stream"]


def test_read_body_limit():
    async def chunks():
        for _ in range(5):
            yield b"x" * 10

    assert asyncio.run(summarize.read_body(chunks(), limit=100)) == b"x" * 50
    assert asyncio.run(summarize.read_body(chunks(), limit=30)) is None
