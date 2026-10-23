"""Talk 파일 요약: 파일 바이트 → 글 → (길면 조각마다 메모) → 요약 스트리밍 → SSE.

LLM 을 직접 부르지 않는다. app.py 가 Ollama 호출([Llm])과 LLM 에게 줄 문장([SummaryPrompts])을 넘긴다 —
LLM 에게 주는 문장은 app.py 에만 둔다. chroma 도 쓰지 않아 테스트에서 대역 LLM 으로 그대로 돌린다.

SSE 순서 (앱·브릿지와의 계약은 shared 의 ChatApi.kt)
  : connected                      연결되자마자
  event: progress {done, total}    긴 문서만. 처음에 0/total, 조각을 하나 읽을 때마다 한 번
  data: {"delta": "..."}           요약 조각, 여러 번
  event: error {message, code}     실패하면. code 는 SummaryErrorCode 값이거나 빈 문자열.
                                   message 는 기록용 영어이고 앱은 화면에 쓰지 않는다(문구는 code 로 고른다)
  event: done {}                   마지막
  : ping                           보낼 것이 PING_SECONDS 동안 없으면 (글 뽑기·조각 읽기·첫 글자 기다리기)

`: connected` 는 글을 뽑기 전에 보낸다. 글 뽑기·다듬기·나누기는 글 전체를 훑으므로 이벤트 루프 밖(스레드)에서 한다.

파일 이름은 URL 쿼리가 아니라 NAME_HEADER 헤더(UTF-8 퍼센트 인코딩)로 받는다. 요청 줄은 uvicorn 접근 로그에 그대로
남기 때문이다 — 브릿지가 로그에 파일 이름을 남기지 않는 것과 같은 기준이다. 서버 로그에는 확장자만 남긴다.
"""

import asyncio
import json
import logging
import math
import re
import time
import urllib.parse
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass

from extract import NO_TEXT, ExtractError, extension_of, extract

# 이보다 짧은 글은 한 번에 요약한다. Gemma 4 의 문맥(16k 토큰)에 프롬프트·답변과 함께 넉넉히 들어가는 길이다.
SHORT_TEXT_CHARS = 10_000
# 긴 글은 문단 경계에서 이 길이 안팎의 조각으로 나눠 조각마다 메모를 받는다.
PIECE_CHARS = 7_000
# 조각 수의 끝. 넘으면 앞부분만 읽고, 요약 끝에 앞부분만 읽었다고 적게 한다.
MAX_PIECES = 12
# 나누기 전에 글을 (MAX_PIECES + KEEP_EXTRA_PIECES) × PIECE_CHARS 자까지만 남긴다. MAX_PIECES 조각은 많아야
# MAX_PIECES × PIECE_CHARS 자이고, 마지막 조각의 경계를 정하는 다음 줄도 PIECE_CHARS 자를 넘지 않으므로,
# 이만큼 남기면 앞 MAX_PIECES 조각이 글 전체를 나눴을 때와 똑같다.
KEEP_EXTRA_PIECES = 2
# 메모를 합친 길이가 이보다 길면 몇 묶음으로 나눠 한 번 더 줄인 뒤 최종 요약에 넣는다(한글 1자 ≈ 0.6 토큰, 16k 문맥 안).
NOTES_CHARS = 14_000
# 앱은 답변을 다음 질문의 대화 기록으로 보낸다. 브릿지 /v1/chat 의 메시지 길이 끝(8000자) 안에 들게 자른다.
MAX_SUMMARY_CHARS = 7_000
# Talk 파일 크기의 끝(shared 의 MAX_SUMMARY_FILE_BYTES 와 같다).
MAX_FILE_BYTES = 20 * 1024 * 1024
MAX_NAME_CHARS = 255
PING_SECONDS = 10
# 브릿지가 요약할 파일의 이름을 싣는 요청 헤더(bridge 의 RagClient.FILE_NAME_HEADER 와 같다).
NAME_HEADER = "X-File-Name"

PIECE_MAX_TOKENS = 900
SUMMARY_MAX_TOKENS = 1_500

PING = ": ping\n\n"
log = logging.getLogger("uvicorn.error")


@dataclass(frozen=True)
class Llm:
    """요약에 쓰는 LLM 호출. complete 는 한 번에 받고, stream 은 글 조각을 차례로 내놓는다.

    둘 다 (system, user, max_tokens) 를 받는다.
    """

    complete: Callable[[str, str, int], Awaitable[str]]
    stream: Callable[[str, str, int], AsyncIterator[str]]


Prompt = tuple[str, str]


@dataclass(frozen=True)
class SummaryPrompts:
    """LLM 에게 줄 (system, user) 를 만드는 함수들. 문장은 app.py 에 있다."""

    whole: Callable[[str, str], Prompt]
    """(파일 이름, 글 전체) → 짧은 문서를 한 번에 요약"""
    piece: Callable[[str, int, int, str], Prompt]
    """(파일 이름, 조각 번호(1부터), 조각 수, 조각) → 조각 메모"""
    condense: Callable[[str, list[str]], Prompt]
    """(파일 이름, 메모 묶음) → 메모 하나로 줄이기"""
    final: Callable[[str, list[str], bool], Prompt]
    """(파일 이름, 메모들, 앞부분만 읽었는지) → 메모를 합쳐 최종 요약"""


def sse(data: dict, event: str | None = None) -> str:
    head = f"event: {event}\n" if event else ""
    return f"{head}data: {json.dumps(data, ensure_ascii=False)}\n\n"


# ---------- 글 다듬기 · 나누기 ----------

def decode_name_header(value: str) -> str | None:
    """NAME_HEADER 값(UTF-8 퍼센트 인코딩, 공백은 %20)을 파일 이름으로 푼다. '+' 는 글자 그대로다.

    인코딩하지 않은 글자(ASCII 밖)가 섞였거나, 풀어도 UTF-8 이 아니거나, 비어 있으면 None.
    """
    if not value.isascii():
        return None
    try:
        name = urllib.parse.unquote(value, encoding="utf-8", errors="strict")
    except UnicodeDecodeError:
        return None
    return name if name.strip() else None


def clean_name(name: str) -> str:
    """경로를 떼고 제어 문자를 걷은 파일 이름. LLM 에게 문서 이름으로 보여 준다."""
    base = name.replace("\\", "/").rsplit("/", 1)[-1]
    return re.sub(r"[\x00-\x1f\x7f]", "", base).strip()[:MAX_NAME_CHARS]


def tidy(text: str) -> str:
    """줄바꿈을 맞추고, 보이지 않는 문자를 지우고, 연속 공백과 빈 줄을 줄인다."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[​-‍⁠﻿]", "", text)
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", text)
    text = re.sub(r"[ \t 　]+", " ", text)
    text = re.sub(r" ?\n ?", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_pieces(text: str, size: int = PIECE_CHARS) -> list[str]:
    """size 글자를 넘지 않는 조각으로 나눈다. 줄 경계에서 자르되, 되도록 문단(빈 줄) 경계에서 자른다.

    한 줄이 size 보다 길면 문장 끝이나 띄어쓰기에서 자른다. 마지막 조각만 짧게 남지 않게, 조각 수를 늘리지 않는
    가장 작은 길이로 다시 나눠 조각 길이를 고르게 맞춘다.
    """
    if not text.strip():
        return []
    lines: list[tuple[str, str]] = []
    for number, paragraph in enumerate(text.split("\n\n")):
        for index, line in enumerate(paragraph.split("\n")):
            sep = "\n\n" if number and not index else "\n"
            for part_index, part in enumerate(split_line(line, size)):
                lines.append((sep if not part_index else " ", part))

    pieces = pack_lines(lines, size)
    if 1 < len(pieces) <= MAX_PIECES:
        target = sum(map(len, pieces)) / len(pieces)
        while target < size:
            even = pack_lines(lines, int(target))
            if len(even) <= len(pieces):
                return even
            target *= 1.04
    return pieces


def pack_lines(lines: list[tuple[str, str]], limit: int) -> list[str]:
    """줄을 차례로 담아 limit 을 넘기 전에 조각을 끊는다. 다음 줄이 새 문단이 아니면 앞쪽 문단 경계로 물러나 끊는다."""
    pieces: list[str] = []
    current: list[tuple[str, str]] = []
    length = 0
    for sep, line in lines:
        if current and length + len(sep) + len(line) > limit:
            cut = len(current) if sep == "\n\n" else paragraph_cut(current, limit)
            pieces.append(join_lines(current[:cut]))
            current = current[cut:]
            length = sum(len(s) + len(l) for s, l in current)
            if current and length + len(sep) + len(line) > limit:
                pieces.append(join_lines(current))
                current, length = [], 0
        current.append((sep, line))
        length += len(sep) + len(line)
    if current:
        pieces.append(join_lines(current))
    return pieces


def paragraph_cut(lines: list[tuple[str, str]], target: int) -> int:
    """조각을 자를 자리. 앞부분이 target 의 60% 를 넘는 마지막 문단 경계가 있으면 거기, 없으면 끝."""
    length = 0
    best = len(lines)
    for index, (sep, line) in enumerate(lines):
        if index and sep == "\n\n" and length >= target * 0.6:
            best = index
        length += len(sep) + len(line)
    return best


def join_lines(lines: list[tuple[str, str]]) -> str:
    return "".join(f"{sep}{line}" if index else line for index, (sep, line) in enumerate(lines))


def split_line(line: str, size: int) -> list[str]:
    parts = []
    while len(line) > size:
        window = line[:size]
        cut = max((window.rfind(mark) + len(mark) for mark in (". ", "? ", "! ", "。") if mark in window), default=-1)
        if cut < size // 2:
            cut = window.rfind(" ") + 1
        if cut < size // 2:
            cut = size
        parts.append(line[:cut].rstrip())
        line = line[cut:].lstrip()
    if line or not parts:
        parts.append(line)
    return parts


def note_groups(notes: list[str], limit: int) -> list[list[str]]:
    """메모를 차례대로, 길이가 비슷한 묶음 몇 개로 나눈다. 묶음 하나가 limit 안팎이 되게 한다."""
    count = max(2, math.ceil(sum(map(len, notes)) / limit))
    count = min(count, len(notes))
    target = sum(map(len, notes)) / count
    groups, current, length = [], [], 0
    for note in notes:
        if current and length + len(note) > target * 1.1 and len(groups) < count - 1:
            groups.append(current)
            current, length = [], 0
        current.append(note)
        length += len(note)
    groups.append(current)
    return groups


# ---------- 요약 흐름 ----------

async def read_body(chunks: AsyncIterator[bytes], limit: int | None = None) -> bytes | None:
    """요청 본문을 읽는다. limit(기본 MAX_FILE_BYTES)을 넘으면 더 읽지 않고 None."""
    limit = MAX_FILE_BYTES if limit is None else limit
    body = bytearray()
    async for chunk in chunks:
        body.extend(chunk)
        if len(body) > limit:
            return None
    return bytes(body)


async def summary_stream(name: str, data: bytes | None, llm: Llm, prompts: SummaryPrompts,
                         ping_seconds: float = PING_SECONDS) -> AsyncIterator[str]:
    """요약 SSE 전체. 실패해도 error 뒤에 done 으로 끝낸다."""
    yield ": connected\n\n"
    started = time.monotonic()
    try:
        if data is None:
            raise ValueError(f"file is larger than {MAX_FILE_BYTES} bytes")
        async for item in with_pings(summary_events(name, data, llm, prompts), ping_seconds):
            yield item
        log.info("summarize: done in %.1fs", time.monotonic() - started)
    except ExtractError as e:
        log.info("summarize: cannot extract (%s) %s", e.code, e.message)
        yield sse({"message": e.message, "code": e.code}, event="error")
    except Exception as e:
        log.warning("summarize: failed after %.1fs: %r", time.monotonic() - started, e)
        yield sse({"message": str(e) or e.__class__.__name__, "code": ""}, event="error")
    yield sse({}, event="done")


@dataclass(frozen=True)
class Prepared:
    """요약할 글. pieces 가 비었으면 text 를 한 번에 요약하고, 아니면 pieces 를 조각마다 메모한다.

    truncated 는 문서 뒷부분을 읽지 않았다는 뜻, chars 는 다듬은 글 전체의 길이(앞부분만 남기기 전)다.
    """

    text: str
    pieces: list[str]
    truncated: bool
    chars: int


def prepare_text(name: str, data: bytes) -> Prepared:
    """글을 뽑아 다듬고, 길면 앞 MAX_PIECES 조각으로 나눈다. 글 전체를 훑는 일이라 스레드에서 부른다.

    나누기 전에 필요한 만큼만 남겨 수십만 자를 다 나누지 않는다. 앞부분만 읽는지는 남기기 전 길이와
    글 뽑기 단계의 상한(Extracted.truncated)으로 판단한다. 글 뽑기에서 잘린 문서는 짧아도 조각으로 읽어
    최종 요약에 앞부분만 읽었다고 적게 한다.
    """
    extracted = extract(name, data)
    text = tidy(extracted.text)
    if not text:
        raise ExtractError(NO_TEXT, "no text after cleanup")
    if len(text) <= SHORT_TEXT_CHARS and not extracted.truncated:
        return Prepared(text=text, pieces=[], truncated=False, chars=len(text))

    keep = (MAX_PIECES + KEEP_EXTRA_PIECES) * PIECE_CHARS
    pieces = split_pieces(text[:keep], PIECE_CHARS)
    truncated = extracted.truncated or len(text) > keep or len(pieces) > MAX_PIECES
    return Prepared(text="", pieces=pieces[:MAX_PIECES], truncated=truncated, chars=len(text))


async def summary_events(name: str, data: bytes, llm: Llm, prompts: SummaryPrompts) -> AsyncIterator[str]:
    """글을 뽑아 짧으면 한 번에, 길면 조각마다 메모(progress)를 받은 뒤 합쳐서 요약 조각(delta)을 내놓는다."""
    name = clean_name(name)
    started = time.monotonic()
    prepared = await asyncio.to_thread(prepare_text, name, data)
    kind = extension_of(name) or "?"

    if not prepared.pieces:
        log.info("summarize: %s %d bytes → %d chars, one pass (extract %.1fs)",
                 kind, len(data), prepared.chars, time.monotonic() - started)
        async for item in deltas(llm.stream(*prompts.whole(name, prepared.text), SUMMARY_MAX_TOKENS)):
            yield item
        return

    pieces, truncated = prepared.pieces, prepared.truncated
    total = len(pieces)
    log.info("summarize: %s %d bytes → %d chars, %d pieces%s (extract %.1fs)",
             kind, len(data), prepared.chars, total, " (truncated)" if truncated else "", time.monotonic() - started)

    yield sse({"done": 0, "total": total}, event="progress")
    notes = []
    for number, piece in enumerate(pieces, 1):
        step = time.monotonic()
        notes.append((await llm.complete(*prompts.piece(name, number, total, piece), PIECE_MAX_TOKENS)).strip())
        log.info("summarize: piece %d/%d %d chars → note %d chars (%.1fs)",
                 number, total, len(piece), len(notes[-1]), time.monotonic() - step)
        yield sse({"done": number, "total": total}, event="progress")

    notes = await condense(name, [n for n in notes if n], llm, prompts)
    async for item in deltas(llm.stream(*prompts.final(name, notes, truncated), SUMMARY_MAX_TOKENS)):
        yield item


async def condense(name: str, notes: list[str], llm: Llm, prompts: SummaryPrompts) -> list[str]:
    """메모를 합친 길이가 NOTES_CHARS 를 넘으면 묶음마다 하나로 줄인다. 두 번까지만 한다."""
    for _ in range(2):
        if len(notes) < 2 or sum(map(len, notes)) <= NOTES_CHARS:
            break
        step, before = time.monotonic(), sum(map(len, notes))
        notes = [
            (await llm.complete(*prompts.condense(name, group), PIECE_MAX_TOKENS)).strip()
            for group in note_groups(notes, NOTES_CHARS)
        ]
        log.info("summarize: condensed notes %d → %d chars in %d groups (%.1fs)",
                 before, sum(map(len, notes)), len(notes), time.monotonic() - step)
    return notes


async def deltas(source: AsyncIterator[str]) -> AsyncIterator[str]:
    """LLM 글 조각을 delta 이벤트로. 합쳐서 MAX_SUMMARY_CHARS 를 넘으면 거기서 자르고 그만 받는다."""
    length = 0
    try:
        async for text in source:
            if not text:
                continue
            text = text[:MAX_SUMMARY_CHARS - length]
            length += len(text)
            if text:
                yield sse({"delta": text})
            if length >= MAX_SUMMARY_CHARS:
                break
    finally:
        close = getattr(source, "aclose", None)
        if close:
            await close()


class _Failure:
    def __init__(self, error: BaseException):
        self.error = error


_END = object()


async def with_pings(source: AsyncIterator[str], seconds: float) -> AsyncIterator[str]:
    """source 를 다른 태스크에서 돌리며 나오는 대로 넘기고, seconds 초 동안 나온 것이 없으면 ping 을 끼운다.

    조각 메모처럼 한 번 부르는 데 수십 초 걸리는 단계에서도 프록시·터널이 연결을 끊지 않게 한다.
    받는 쪽이 끊으면(이 생성기가 닫히면) source 를 돌리던 태스크도 멈춘다 — Ollama 요청도 함께 끊긴다.
    """
    queue: asyncio.Queue = asyncio.Queue()

    async def pump():
        try:
            async for item in source:
                await queue.put(item)
        except Exception as e:
            await queue.put(_Failure(e))
        else:
            await queue.put(_END)

    task = asyncio.create_task(pump())
    getter: asyncio.Future | None = None
    try:
        while True:
            getter = getter or asyncio.ensure_future(queue.get())
            done, _ = await asyncio.wait({getter}, timeout=seconds)
            if not done:
                yield PING
                continue
            item, getter = getter.result(), None
            if item is _END:
                return
            if isinstance(item, _Failure):
                raise item.error
            yield item
    finally:
        if getter is not None:
            getter.cancel()
        task.cancel()
