"""로컬 RAG 서버: Ollama(Gemma 4 + bge-m3) + Chroma.

브릿지 서버만 이 서버를 부른다. 바깥에 열지 않으므로 127.0.0.1 에서만 띄운다.

엔드포인트
  GET  /v1/health     서버·모델·문서 수 확인
  POST /v1/ingest     docs/ 폴더의 .md/.txt 를 다시 색인
  POST /v1/chat       AI Agent 앱의 질문 → 문서 검색 → 답변을 SSE 로 스트리밍 (마크다운, 문서로만 답함)
                      다른 사람에게 메시지를 보내 달라는 요청이면 답 대신 action 이벤트를 보낸다 — 보내는 일은 앱이 확인을 받고 브릿지가 한다
  POST /v1/summarize  X-File-Name: <퍼센트 인코딩한 파일 이름>, 본문 = Talk 파일 바이트 → 글을 뽑아 요약을 SSE 로 스트리밍
                      긴 문서는 조각마다 메모하며 progress 를 보낸다 (글 뽑기는 extract.py, 흐름은 summarize.py)
"""

import json
import os
import re
import threading
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import chromadb
import httpx
from fastapi import APIRouter, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from summarize import NAME_HEADER, Llm, SummaryPrompts, decode_name_header, read_body, sse, summary_stream

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://localhost:11434")
CHAT_MODEL = os.getenv("CHAT_MODEL", "gemma4:26b")
EMBED_MODEL = os.getenv("EMBED_MODEL", "bge-m3")
TOP_K = int(os.getenv("TOP_K", "4"))
# Gemma 4 는 기본으로 생각(thinking) 모드가 켜져 있어 첫 글자가 몇 초 늦게 나온다. RAG 답변에는 꺼 두는 편이 빠르다.
THINK = os.getenv("THINK", "0") == "1"
# 후속 질문("그거 어디서 해?")을 앞 대화를 반영한 독립 질문으로 바꿔서 검색한다
REWRITE = os.getenv("REWRITE", "1") == "1"
# 모델을 메모리에 붙잡아 두는 시간. Talk 의 AI 요청은 30초 안에 답해야 해서 모델을 다시 올리는 시간을 피한다.
KEEP_ALIVE = os.getenv("KEEP_ALIVE", "30m")

BASE_DIR = Path(__file__).parent
DOCS_DIR = BASE_DIR / "docs"
# 벡터 DB 폴더. 테스트는 임시 폴더를 넣어 실행 중인 서버의 DB 를 건드리지 않는다.
CHROMA_DIR = os.getenv("CHROMA_DIR", str(BASE_DIR / "chroma_data"))

# 문서에 답이 없을 때 하는 말. 답변 전체가 이 말뿐이면 done 이벤트에 notFound 를 실어, 앞서 보낸 근거를 앱이 지운다.
NOT_FOUND = "문서에서 찾을 수 없습니다"

SYSTEM_PROMPT = f"""당신은 사내 문서 도우미입니다.
- 항상 자연스러운 한국어로만 답변합니다.
- 아래 [참고 문서]에 있는 내용만 근거로 답합니다. 문서에 없는 내용은 "{NOT_FOUND}"라고 말합니다.
- 답변은 마크다운으로 간결하게 정리하고, 끝에 근거로 쓴 문서 이름을 적습니다. 문서에서 찾지 못했으면 문서 이름을 적지 않습니다.
- 사용자가 다른 사람에게 메시지를 보내(전해·알려·말해) 달라고 하면 답하지 말고 send_talk_message 도구를 씁니다.
  받는 사람은 이름만 있어도 됩니다 — 아이디를 몰라도 사용자가 부른 그대로 적습니다.
  내용이 "방금 그 내용"처럼 앞 대화를 가리키면 그 내용을 받는 사람이 그대로 읽을 수 있는 문장으로 채웁니다.
  "보냈습니다" 같은 글을 직접 쓰지 않습니다 — 메시지는 도구를 불러야만 갑니다."""

# 메시지 보내기 도구. LLM 은 받는 사람과 내용만 정하고, 실제로 보내는 일은 앱이 사용자 확인을 받은 뒤 브릿지가 한다.
SEND_MESSAGE_TOOL = {
    "type": "function",
    "function": {
        "name": "send_talk_message",
        "description": "Talk 메신저로 다른 사람에게 메시지를 보낸다. 사용자가 누군가에게 메시지·말·내용을 보내/전해/알려 달라고 할 때만 쓴다. 받는 사람은 이름만 알아도 된다.",
        "parameters": {
            "type": "object",
            "properties": {
                "recipient": {"type": "string", "description": "받는 사람. 사용자가 부른 이름이나 아이디 그대로 (예: 이서연, 서연님, test2)"},
                "content": {"type": "string", "description": "보낼 메시지 본문. 받는 사람에게 그대로 보낼 완성된 문장"},
            },
            "required": ["recipient", "content"],
        },
    },
}

REWRITE_PROMPT = """당신은 사내 문서 검색용 질문을 만드는 도구입니다.
[이전 대화]와 [마지막 질문]을 보고, 마지막 질문을 이전 대화 없이도 뜻이 통하는 한국어 질문 한 문장으로 다시 씁니다.
- "그거", "거기", "that" 같은 지시어는 가리키는 대상을 구체적으로 적습니다.
- 이전 대화의 주제(예: 병가, 재택근무, 테스트 기기)를 빠뜨리지 않습니다.
- 질문에 답하지 말고, 다시 쓴 질문 한 문장만 출력합니다."""

# ---------- 문서 요약 (POST /v1/summarize) ----------

SUMMARY_FORMAT = """[형식] 마크다운
첫 줄: 문서 전체를 요약한 한 문장 (제목·글머리표·굵은 글씨 없이 문장만)

### 주요 내용
- 핵심 내용을 글머리표 5~8개로. 하나에 한두 문장

### 핵심 수치·일정
- 문서에 나온 숫자·금액·비율·날짜·기한·담당자를 글머리표로
- 문서에 이런 정보가 없으면 이 절은 제목까지 통째로 뺍니다"""


def summary_system(source: str) -> str:
    """요약 시스템 프롬프트. source 는 요약할 글의 이름표([문서] 또는 [메모])."""
    return f"""당신은 사내 문서를 요약하는 도우미입니다.
- 항상 자연스러운 한국어로 씁니다. 문서가 영어 등 다른 언어로 되어 있어도 한국어로 요약합니다.
- {source}에 적힌 내용만 씁니다. 적혀 있지 않은 사실·숫자·이름·평가·전망을 지어내거나 짐작해 덧붙이지 않습니다.
- 숫자·금액·날짜·이름은 적힌 그대로 옮깁니다. 계산해서 새 숫자를 만들지 않습니다.
- 문서가 짧으면 글머리표 수를 줄입니다. 개수를 채우려고 같은 말을 되풀이하거나 내용을 늘리지 않습니다.
- 인사말, "요약입니다" 같은 머리말, 맺음말 없이 아래 형식만 출력합니다.

{SUMMARY_FORMAT}"""


SUMMARY_PIECE_PROMPT = """당신은 긴 문서를 여러 조각으로 나눠 읽으며 메모하는 도우미입니다.
- [문서 조각]의 핵심 내용을 한국어 글머리표 메모로 정리합니다. 많아야 8개, 하나에 한 문장.
- 숫자·금액·비율·날짜·기한·이름·담당은 빠뜨리지 말고 원문 그대로 옮깁니다.
- 조각에 없는 내용을 짐작해 덧붙이지 않습니다. 조각이 문장 중간에서 시작하거나 끊겨도 있는 내용만 메모합니다.
- 인사말·머리말 없이 메모만 출력합니다."""

SUMMARY_CONDENSE_PROMPT = """당신은 긴 문서를 읽으며 적은 메모를 줄이는 도우미입니다.
- 여러 [메모]를 하나로 합쳐 한국어 글머리표 많아야 12개로 줄입니다. 겹치는 내용은 하나로 합칩니다.
- 숫자·금액·비율·날짜·기한·이름·담당은 원문 그대로 남깁니다.
- 메모에 없는 내용을 덧붙이지 않습니다. 인사말·머리말 없이 메모만 출력합니다."""

# 문서가 길어 앞부분만 읽었을 때 요약 끝에 그대로 적게 하는 문장
SUMMARY_TRUNCATED_NOTE = "※ 문서가 길어 앞부분만 읽고 요약했어요."


def summary_whole(name: str, text: str) -> tuple[str, str]:
    return summary_system("[문서]"), f"[파일 이름] {name}\n\n[문서]\n{text}\n[문서 끝]\n\n위 문서를 [형식]에 맞춰 요약해 주세요."


def summary_piece(name: str, number: int, total: int, piece: str) -> tuple[str, str]:
    return SUMMARY_PIECE_PROMPT, f"[파일 이름] {name}\n[위치] 전체 {total}개 조각 중 {number}번째\n\n[문서 조각]\n{piece}\n[조각 끝]"


def summary_condense(name: str, notes: list[str]) -> tuple[str, str]:
    body = "\n\n".join(f"[메모 {i}]\n{note}" for i, note in enumerate(notes, 1))
    return SUMMARY_CONDENSE_PROMPT, f"[파일 이름] {name}\n\n{body}"


def summary_final(name: str, notes: list[str], truncated: bool) -> tuple[str, str]:
    system = summary_system("[메모]") + """

[메모]는 긴 문서를 앞에서부터 조각으로 나눠 읽으며 차례로 적은 것입니다. 조각별로 따로 요약하지 말고, 메모 전체를 문서 하나의 요약으로 합칩니다."""
    if truncated:
        system += f"""
문서가 너무 길어 앞부분만 읽었습니다. 요약의 맨 마지막 줄에 다음 문장을 그대로 적습니다: {SUMMARY_TRUNCATED_NOTE}"""
    body = "\n\n".join(f"[메모 {i}]\n{note}" for i, note in enumerate(notes, 1))
    return system, f"[파일 이름] {name}\n\n{body}\n\n위 메모로 문서 전체를 [형식]에 맞춰 요약해 주세요."


SUMMARY_PROMPTS = SummaryPrompts(whole=summary_whole, piece=summary_piece, condense=summary_condense, final=summary_final)
SUMMARY_TEMPERATURE = 0.2
# 조각 메모는 스트리밍하지 않아 한 번에 수십 초 걸린다. 넉넉히 기다린다.
SUMMARY_TIMEOUT = 300



def warm_up():
    """모델 두 개를 메모리에 올려 둔다. 첫 질문이 모델을 올리느라 늦지 않게 한다. 실패해도 서버는 그대로 뜬다."""
    try:
        httpx.post(f"{OLLAMA_URL}/api/embed", json={"model": EMBED_MODEL, "input": ["warm up"], "keep_alive": KEEP_ALIVE}, timeout=300)
        httpx.post(f"{OLLAMA_URL}/api/generate", json={"model": CHAT_MODEL, "keep_alive": KEEP_ALIVE}, timeout=300)
    except Exception:
        pass


@asynccontextmanager
async def lifespan(_: FastAPI):
    threading.Thread(target=warm_up, daemon=True).start()
    yield


db = chromadb.PersistentClient(path=CHROMA_DIR)
collection = db.get_or_create_collection("docs", metadata={"hnsw:space": "cosine"})
app = FastAPI(title="Local RAG", lifespan=lifespan)
v1 = APIRouter(prefix="/v1")


class SendAction(BaseModel):
    recipient: str
    content: str
    sent: bool  # 사용자가 확인해 실제로 보냈는지


class Message(BaseModel):
    role: str  # "user" | "assistant"
    content: str = ""
    sendAction: SendAction | None = None  # 이 차례에 AI 가 답 대신 정한 메시지 보내기 — LLM 에는 도구 호출로 넣는다


class ChatRequest(BaseModel):
    messages: list[Message]


# ---------- 임베딩 / 청크 / LLM ----------

def embed(texts: list[str]) -> list[list[float]]:
    res = httpx.post(f"{OLLAMA_URL}/api/embed", json={"model": EMBED_MODEL, "input": texts, "keep_alive": KEEP_ALIVE}, timeout=120)
    res.raise_for_status()
    return res.json()["embeddings"]


async def aembed(texts: list[str]) -> list[list[float]]:
    async with httpx.AsyncClient(timeout=120) as client:
        res = await client.post(f"{OLLAMA_URL}/api/embed", json={"model": EMBED_MODEL, "input": texts, "keep_alive": KEEP_ALIVE})
        res.raise_for_status()
        return res.json()["embeddings"]


def chunk(text: str, size: int = 250, overlap: int = 60) -> list[str]:
    """문단 단위로 모아 size 글자 안팎의 조각을 만든다. 조각 사이에 overlap 글자를 겹친다."""
    paragraphs = [p.strip() for p in text.split("\n\n") if p.strip()]
    chunks, current = [], ""
    for p in paragraphs:
        if current and len(current) + len(p) > size:
            chunks.append(current)
            current = current[-overlap:] + "\n\n" + p
        else:
            current = f"{current}\n\n{p}" if current else p
    if current:
        chunks.append(current)
    return chunks


async def complete(system: str, user: str, max_tokens: int, temperature: float = 0.3, timeout: float = 120) -> str:
    """스트리밍 없이 한 번에 답을 받는다."""
    payload = {
        "model": CHAT_MODEL,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": temperature,
        "max_tokens": max_tokens,
        "keep_alive": KEEP_ALIVE,
    }
    if not THINK:
        payload["reasoning_effort"] = "none"
    async with httpx.AsyncClient(timeout=timeout) as client:
        res = await client.post(f"{OLLAMA_URL}/v1/chat/completions", json=payload)
        res.raise_for_status()
        return res.json()["choices"][0]["message"]["content"].strip()


async def stream_completion(system: str, user: str, max_tokens: int, temperature: float = 0.3,
                            timeout: float = 300) -> AsyncIterator[str]:
    """도구 없이 답을 스트리밍으로 받아 글 조각을 차례로 내놓는다."""
    payload = {
        "model": CHAT_MODEL,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "stream": True,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "keep_alive": KEEP_ALIVE,
    }
    if not THINK:
        payload["reasoning_effort"] = "none"
    async with httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=10)) as client:
        async with client.stream("POST", f"{OLLAMA_URL}/v1/chat/completions", json=payload) as res:
            res.raise_for_status()
            async for line in res.aiter_lines():
                if not line.startswith("data: ") or line == "data: [DONE]":
                    continue
                content = json.loads(line[6:])["choices"][0]["delta"].get("content")
                if content:
                    yield content


async def rewrite_query(messages: list[Message]) -> str:
    """앞 대화가 있으면 마지막 질문을 독립 질문으로 다시 쓴다. 첫 질문이거나 실패하면 원래 질문을 그대로 쓴다."""
    question = messages[-1].content
    if not REWRITE or len(messages) < 2:
        return question

    history = "\n".join(
        f"{'사용자' if m.role == 'user' else '도우미'}: {as_text(m)[:300]}"  # 긴 답변은 앞부분만
        for m in messages[-7:-1]
    )
    try:
        rewritten = await complete(REWRITE_PROMPT, f"[이전 대화]\n{history}\n\n[마지막 질문]\n{question}", 100, 0)
        return rewritten.strip('"').splitlines()[0] or question
    except Exception:
        return question


async def search(query: str, k: int = TOP_K) -> list[dict]:
    """질문을 임베딩해 가장 가까운 조각 k 개"""
    hits = collection.query(query_embeddings=await aembed([query]), n_results=k)
    return [
        {"doc": doc, "source": meta["source"], "score": 1 - dist}
        for doc, meta, dist in zip(hits["documents"][0], hits["metadatas"][0], hits["distances"][0])
    ]


def context_of(hits: list[dict]) -> str:
    return "\n\n---\n\n".join(f"[{h['source']}]\n{h['doc']}" for h in hits)


def sources_of(hits: list[dict]) -> list[dict]:
    return [{"source": h["source"], "score": round(h["score"], 3)} for h in hits]


def as_text(m: Message) -> str:
    """질문 재작성에 쓰는 한 줄. 메시지 보내기는 누구에게 무엇을 보내려 했는지로 적는다."""
    if m.sendAction:
        return f"({m.sendAction.recipient}에게 메시지 보내기: {m.sendAction.content})"
    return m.content


def llm_messages(history: list[Message]) -> list[dict]:
    """대화 기록을 LLM 메시지로 바꾼다. 앞서 정한 메시지 보내기는 도구 호출과 그 결과로 넣는다.

    글("…에게 보냈습니다")로 넣으면 모델이 다음 보내기 요청에서 도구를 부르지 않고 그 글만 흉내 내, 보냈다고 말하고
    아무것도 보내지 않는다.
    """
    out = []
    for i, m in enumerate(history):
        if m.role == "assistant" and m.sendAction:
            call_id = f"call_{i}"
            args = json.dumps({"recipient": m.sendAction.recipient, "content": m.sendAction.content}, ensure_ascii=False)
            out.append({"role": "assistant", "content": "", "tool_calls": [
                {"id": call_id, "type": "function", "function": {"name": "send_talk_message", "arguments": args}},
            ]})
            out.append({"role": "tool", "tool_call_id": call_id, "content": "보냈습니다" if m.sendAction.sent else "사용자가 보내지 않기로 했습니다"})
        else:
            out.append({"role": m.role, "content": m.content})
    return out


def is_not_found(answer: str) -> bool:
    """답변 전체가 "문서에서 찾을 수 없습니다" 한마디뿐인지. 일부라도 답한 답변은 근거가 있으니 아니다.

    "요청하신 복장 규정은 문서에서 찾을 수 없습니다." 처럼 앞뒤에 짧은 말이 붙는 것까지 본다.
    """
    text = re.sub(r"[\s.!*_`>#\-\[\]:]", "", answer)
    key = NOT_FOUND.replace(" ", "")
    return key in text and len(text) - len(key) <= 40


# ---------- 엔드포인트 ----------

@v1.get("/health")
def health():
    return {"status": "ok", "chatModel": CHAT_MODEL, "embedModel": EMBED_MODEL, "chunks": collection.count()}


@v1.post("/ingest")
def ingest():
    existing = collection.get()["ids"]
    if existing:
        collection.delete(ids=existing)

    ids, texts, metas = [], [], []
    for path in sorted(DOCS_DIR.glob("*")):
        if path.suffix not in (".md", ".txt"):
            continue
        for i, c in enumerate(chunk(path.read_text(encoding="utf-8"))):
            ids.append(f"{path.name}#{i}")
            texts.append(c)
            metas.append({"source": path.name})

    if texts:
        collection.add(ids=ids, documents=texts, embeddings=embed(texts), metadatas=metas)
    return {"files": len({m["source"] for m in metas}), "chunks": len(texts)}


# 터널·프록시가 스트리밍을 모아 두지 않게 한다
STREAM_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no"}


@v1.post("/chat")
async def chat(req: ChatRequest):
    async def stream():
        # 연결되자마자 한 줄을 보내 프록시가 응답 시작을 기다리며 끊지 않게 한다
        yield ": connected\n\n"
        answer = ""  # 문서에서 답을 못 찾았는지 마지막에 보려고 모은다
        calls: dict[int, dict] = {}  # 도구 호출은 조각으로 올 수 있어 index 별로 모은다
        try:
            # 1) 검색: 후속 질문이면 앞 대화를 반영해 다시 쓴 질문으로 가장 가까운 조각 TOP_K 개
            query = await rewrite_query(req.messages)
            hits = await search(query)
            retrieval = {"sources": sources_of(hits)}
            if query != req.messages[-1].content:
                retrieval["query"] = query  # 재작성된 경우에만 앱에 알려 준다
            yield sse(retrieval, event="sources")

            # 2) 프롬프트: 시스템 + 참고 문서 + 이전 대화 + 이번 질문
            messages = [{"role": "system", "content": f"{SYSTEM_PROMPT}\n\n[참고 문서]\n{context_of(hits)}"}]
            messages += llm_messages(req.messages[-10:])  # 최근 10개 메시지만 유지

            # 3) Ollama 의 OpenAI 호환 API 를 스트리밍으로 호출하고, 받은 조각을 SSE 로 그대로 넘긴다
            payload = {
                "model": CHAT_MODEL, "messages": messages, "stream": True, "temperature": 0.3,
                "keep_alive": KEEP_ALIVE, "tools": [SEND_MESSAGE_TOOL],
            }
            if not THINK:
                payload["reasoning_effort"] = "none"
            async with httpx.AsyncClient(timeout=None) as client:
                async with client.stream("POST", f"{OLLAMA_URL}/v1/chat/completions", json=payload) as res:
                    res.raise_for_status()
                    async for line in res.aiter_lines():
                        if not line.startswith("data: ") or line == "data: [DONE]":
                            continue
                        delta = json.loads(line[6:])["choices"][0]["delta"]
                        if delta.get("content"):
                            answer += delta["content"]
                            yield sse({"delta": delta["content"]})
                        for call in delta.get("tool_calls") or []:
                            slot = calls.setdefault(call.get("index", 0), {"name": "", "arguments": ""})
                            slot["name"] += call.get("function", {}).get("name") or ""
                            slot["arguments"] += call.get("function", {}).get("arguments") or ""
            for call in calls.values():
                if call["name"] != "send_talk_message":
                    continue
                args = json.loads(call["arguments"] or "{}")
                if args.get("recipient") and args.get("content"):
                    yield sse({"type": "send_message", "recipient": args["recipient"], "content": args["content"]}, event="action")
        except Exception as e:  # 모델 미설치, Ollama 꺼짐 등
            yield sse({"message": str(e) or e.__class__.__name__}, event="error")
        # 근거는 답변보다 먼저 보냈으므로, 쓸모없었다는 것은 여기서 알린다
        yield sse({"notFound": True} if not calls and is_not_found(answer) else {}, event="done")

    return StreamingResponse(stream(), media_type="text/event-stream", headers=STREAM_HEADERS)


async def summary_complete(system: str, user: str, max_tokens: int) -> str:
    return await complete(system, user, max_tokens, SUMMARY_TEMPERATURE, timeout=SUMMARY_TIMEOUT)


def summary_stream_llm(system: str, user: str, max_tokens: int) -> AsyncIterator[str]:
    return stream_completion(system, user, max_tokens, SUMMARY_TEMPERATURE, timeout=SUMMARY_TIMEOUT)


@v1.post("/summarize")
async def summarize(request: Request, encoded_name: str = Header(alias=NAME_HEADER, min_length=1)):
    """본문(파일 바이트)을 요약한다. 형식은 NAME_HEADER 헤더에 퍼센트 인코딩해 실은 파일 이름의 확장자로 가린다.

    이름을 URL 쿼리로 받지 않는다 — 요청 줄은 uvicorn 접근 로그에 남는다. 헤더가 없거나 잘못 인코딩됐으면 422,
    그 밖의 실패는 SSE(error → done)로 알린다.
    """
    name = decode_name_header(encoded_name)
    if name is None:
        raise HTTPException(status_code=422, detail=f"{NAME_HEADER} must be percent-encoded UTF-8")
    data = await read_body(request.stream())
    llm = Llm(complete=summary_complete, stream=summary_stream_llm)
    return StreamingResponse(summary_stream(name, data, llm, SUMMARY_PROMPTS), media_type="text/event-stream",
                             headers=STREAM_HEADERS)


app.include_router(v1)
