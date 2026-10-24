# AI_Agent

**로컬 LLM 으로 사내 문서에 답하고, 말로 시키면 Talk 메신저에 메시지를 보내는 AI Agent 앱(Android · iOS)과 서버들.**
LLM·문서·대화 기록은 모두 사내(이 Mac)에 머문다. 메신저는 같은 회사의 [Talk](../Talk) 를 그대로 쓴다.
Talk 쪽에서 고친 것은 파일 대화의 "AI 요약" 메뉴 하나다(이 앱을 링크로 연다).

```
[Talk 앱]──aiagent://summarize──▶[AI Agent 앱]──로그인·대화·요약(SSE)──▶[브릿지 :8095]──▶[RAG 서버 :8000]──▶[Ollama: gemma4:26b · bge-m3]
 파일 대화 "AI 요약"                 Android·iOS                          Kotlin/Ktor  │      Python·Chroma
                                                                                    └──▶[Talk 서버 :8090]  로그인 확인 · 사람 찾기 · 메시지 보내기 · 파일 받기
```

| 할 수 있는 것 | 예 |
|---|---|
| 사내 문서로만 답하기 (RAG) | "갑자기 아프면 출근 못 한다고 어떻게 알려야 해?" → 휴가 규정을 근거로 답하고 근거 문서를 보여 준다 |
| 후속 질문 이해 | "그거 어느 메뉴에서 해?" → 앞 대화를 반영해 검색어를 다시 쓴다(화면에 검색어 표시) |
| Talk 로 메시지 보내기 (에이전트) | "이서연에게 내일 회의가 10시로 바뀌었다고 보내줘" → 확인 카드 → [보내기] → Talk 에 본인 이름으로 전송 |
| 앞 답변을 그대로 보내기 | "방금 그 내용 test3한테도 알려줘" → 앞 답변을 내용으로 채운 확인 카드 |
| Talk 문서 요약 | Talk 에서 문서 파일 대화를 길게 눌러 "AI 요약" → 이 앱이 열리고 새 대화에 "파일명 요약해 줘"와 요약 답변 |

---

## 앱

Talk 와 같은 구조(Clean Architecture + MVI, Koin, Voyager, Ktor, SQLDelight)와 같은 모양(색·상단바·말풍선·탭·태블릿 2단)이다.

| 화면 | 내용 |
|---|---|
| 로그인 | Talk 계정(`test1`~`test10` / `1234`). 아래 "서버: …" 줄을 눌러 브릿지 주소를 바꿀 수 있다 |
| AI 대화 목록 | 사람별로 기기에 저장. 길게 눌러 삭제. 태블릿은 목록과 대화방을 나란히 |
| AI 대화방 | 스트리밍(▍)·중지, 마크다운(제목·목록·표·코드), 근거 문서 칩, 다시 쓴 검색어, 추천 질문, **메시지 보내기 확인 카드** |
| 설정 | 계정·로그아웃, 브릿지 주소, 연결 상태(브릿지·Talk·모델·문서 조각 수) |

### 메시지 보내기의 안전장치

- **AI 는 보내지 않는다.** LLM 은 `send_talk_message` 도구로 받는 사람과 내용만 정한다(`event: action`). 보내는 일은 사용자가 카드에서
  [보내기]를 누른 뒤 앱이 브릿지를 불러서 한다.
- **받는 사람은 Talk 에서 찾아 보여 준다.** 같은 이름이 여럿이면 고르게 하고, 찾지 못하면 [보내기]가 눌리지 않는다.
- **로그인한 사람으로 보낸다.** 브릿지는 그 사람의 Talk 토큰으로 Talk 의 기존 API(사람 목록 → 1:1 방 열기 → 대화 보내기)를 부른다.
  Talk 에서는 본인이 보낸 메시지로 보이고, 누구에게 보낼 수 있는지도 Talk 서버의 규칙을 따른다.
- **두 번 가지 않는다.** 앱 메시지 id 를 Talk 대화 id 로 쓴다. 응답을 못 받아 다시 보내도 Talk 는 한 번만 받는다.
- **문서 속 지시를 따르지 않는다.** 참고 문서와 파일 요약은 '자료' 틀로 감싸 LLM 에 넣고, 시스템 프롬프트가 그 안의 지시를 따르지 말라고 한다.
  RAG 서버는 LLM 이 정한 받는 사람의 낱말이 모두 사용자의 이번 말에 있을 때만 보내기 카드를 내고, 아니면 받는 사람을 적어 달라고 답한다.
  브릿지는 Talk 의 AI 계정·자기 자신에게 보내기를 받지 않는다.

### Talk 문서 요약

Talk 의 파일 대화를 길게 눌러 "AI 요약"을 고르면 Talk 가 이 앱을 링크로 연다.

```
aiagent://summarize?fileId=<Talk 파일 id>&fileName=<파일 이름>&userId=<Talk 아이디>&userName=<이름>   (값은 모두 퍼센트 인코딩)
```

- **계정을 맞춘다.** 이 앱에 같은 아이디로 로그인해 있으면 바로 새 대화를 만들어 요약한다. 다른 계정이면 먼저 "지금 계정에서
  로그아웃하고 <아이디> 로 로그인할까요?" 를 묻고, [로그아웃]을 골랐을 때만 로그아웃한 뒤 로그인 칸에 그 아이디를 채운다([취소]면 그대로).
  로그인 전이면 바로 로그인 칸에 아이디를 채운다. 그 계정으로 로그인하면 요약이 이어지고, 다른 계정으로 로그인하면 취소했다고 알린다.
  링크는 아무 앱이나 열 수 있으므로 링크의 이름(userName)은 화면에 쓰지 않고 아이디만 보이며, 몇 초 안에 거듭 오는 링크는 버린다.
- **파일은 볼 수 있는 사람만 받는다.** 브릿지가 로그인한 사람의 Talk 토큰으로 Talk 서버에서 파일을 받는다. Talk 서버는 그 파일이
  있는 방의 사람에게만 파일을 준다. 아무 앱이 링크를 열어도 남의 파일은 요약되지 않는다.
- **형식**: txt·md·csv·tsv·log·json·xml·html, pdf, docx, xlsx, pptx, hwpx, hwp(5.0). 20MB 까지. 스캔한 PDF(글자 없음)·암호 걸린
  문서·옛 doc/xls/ppt·HWP 3.x 는 이유를 알려 주고 요약하지 않는다. 라이브러리는 BSD·MIT 계열만 쓴다(pypdf·python-docx·openpyxl·
  python-pptx·olefile·cryptography). HWP 는 olefile 로 본문 레코드를 직접 읽는다.
- **긴 문서**: 1만 자까지는 한 번에, 그보다 길면 약 7천 자 조각으로 나눠 조각마다 메모를 받은 뒤 합쳐 요약한다(조각마다 진행 표시).
  조각은 12개(약 8만 자)까지 읽고, 넘으면 앞부분만 읽었다고 요약 끝에 적는다. 짧은 문서는 5~10초, 12조각은 약 3분.
- 요약한 문서는 사내 문서 검색 DB(Chroma)에 넣지 않는다. 같은 대화에서 이어 묻는 질문은 요약 글을 대화 기록으로 삼는다.

## 준비

| 필요한 것 | 비고 |
|---|---|
| JDK 17 · Android SDK · Xcode | Talk 와 같다 |
| Python 3.12 이상 | RAG 서버 |
| [Ollama](https://ollama.com) | `ollama pull gemma4:26b` (약 16GB) · `ollama pull bge-m3` (약 1.2GB). 메모리 32GB 이상 권장 |
| Talk 저장소 | 로컬 서버(`:server:runLocal`)를 띄운다. 계정·메시지는 Talk 가 정본이다 |
| Maestro | 실화면 흐름에만 |

```bash
cd rag-server && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```

`local.properties` 에 Android SDK 경로와 **브릿지 접속 키**를 둔다. 앱과 브릿지가 같은 파일에서 키를 읽는다.

```properties
sdk.dir=<Android SDK 경로>
bridge.accessKey=<32자 이상 무작위 값>      # openssl rand -base64 32. 저장소에 넣지 않는다(실제 값은 ../AI_Agent_info)
bridge.baseUrl=<앱의 기본 브릿지 주소>      # 비우면 에뮬레이터 http://10.0.2.2:8095, 시뮬레이터 http://localhost:8095
```

- 앱은 모든 브릿지 요청에 `X-Bridge-Key` 를 싣고, 브릿지는 같은 값일 때만 받는다. 키는 모든 설치본이 같은 값이고 APK·IPA 에서 꺼낼 수
  있으니 빌드를 바깥 사람에게 주지 않는다. 새면 브릿지와 앱의 키를 함께 바꾸고 다시 빌드한다. 앱은 키를 빌드 기본 주소의 호스트와
  로컬 주소(10.0.2.2·localhost·127.0.0.1)에만 싣는다.
- `bridge.baseUrl` 은 https 이거나 로컬 주소여야 빌드된다. 앱의 평문 HTTP 는 로컬 주소에만 열려 있어(Android network_security_config,
  iOS NSAllowsLocalNetworking + 앱의 주소 규칙), 같은 Wi-Fi 의 실기기가 `http://<Mac IP>:8095` 로 붙는 방식은 더 쓰지 않는다.
  실기기는 https 터널 주소(`AI_AGENT_BRIDGE_URL=<터널 주소>` 로 빌드)를 쓴다.

## 실행

순서대로 띄운다. 터미널 다섯 개가 필요하다.

```bash
ollama serve
cd rag-server && .venv/bin/uvicorn app:app --host 127.0.0.1 --port 8000
curl -X POST localhost:8000/v1/ingest                      # rag-server/docs 의 .md/.txt 를 색인 (문서를 바꿀 때마다)
cd ../Talk && ./gradlew :server:runLocal                   # Talk 로컬 서버 8090
./gradlew :bridge:run                                      # 브릿지 127.0.0.1:8095. local.properties 의 bridge.accessKey 를 넘긴다 (실행 구성 "브릿지 실행")
./gradlew :composeApp:installDebug                         # Android
open iosApp/iosApp.xcodeproj                               # iOS — Xcode 에서 시뮬레이터를 골라 실행
```

| 설정 (환경변수) | 기본값 | 뜻 |
|---|---|---|
| 브릿지 `PORT` · `HOST` | `8095` · `127.0.0.1` | 이 Mac 안에서만 받는다. 에뮬레이터(10.0.2.2)·시뮬레이터·터널(Funnel 은 127.0.0.1:8095 로 붙음)은 그대로 된다 |
| 브릿지 `BRIDGE_ACCESS_KEY` | `local.properties` 의 `bridge.accessKey` | 없거나 32자보다 짧으면 브릿지가 뜨지 않는다. `BRIDGE_ALLOW_NO_KEY=1` 은 이 기기 안에서 프록시 없이 바로 온 요청만 받는 시험용이다 |
| 브릿지 `ALLOWED_USER_IDS` | (비움) | 쉼표로 적은 아이디만 로그인할 수 있다. 비우면 모든 Talk 계정 |
| 브릿지 `LOGIN_PER_IP_PER_MINUTE` · `LOGIN_GLOBAL_PER_SECOND` | `10` · `5` | 로그인 시도 한도(넘으면 429). 터널 뒤에서는 X-Forwarded-For 의 마지막 값을 주소로 본다 |
| 브릿지 `LLM_CONCURRENCY` · `LLM_PER_USER_CONCURRENCY` | `4` · `2` | 동시에 도는 대화·요약의 무게 합(요약은 2)과 사람당 동시 개수. 넘으면 503 busy |
| 브릿지 `PEOPLE_PER_USER_HOURLY` | `120` | 사람 찾기 한도 |
| 브릿지 `RAG_URL` · `TALK_URL` | `http://127.0.0.1:8000` · `http://localhost:8090` | |
| 브릿지 `CHAT_PER_USER_HOURLY` | `60` | 한 사람이 한 시간에 보낼 수 있는 질문·메시지 수 |
| RAG `CHAT_MODEL` · `EMBED_MODEL` | `gemma4:26b` · `bge-m3` | 임베딩 모델을 바꾸면 다시 색인한다 |
| RAG `THINK` | `0` | `1` 이면 Gemma 4 생각 모드. 첫 글자까지 약 6초 늦어진다 |
| RAG `REWRITE` | `1` | 후속 질문을 앞 대화를 반영해 다시 써서 검색 |

## 브릿지 API

모든 경로(`/v1/health` 포함)는 접속 키 헤더 `X-Bridge-Key` 가 먼저 필요하고(없거나 틀리면 403 `client_key_invalid`), `/v1/health` 밖은
`Authorization: Bearer <Talk 토큰>` 도 필요하다. 요청·응답 모델은 `shared/` 에 있다.

| 경로 | 내용 |
|---|---|
| `POST /v1/auth/login` · `/v1/auth/logout` | Talk 서버 `/auth/login` 을 대행. 토큰은 Talk 토큰이다 |
| `POST /v1/chat` | 대화 기록을 받아 RAG 서버의 SSE 를 그대로 흘려보낸다. 앞서 정한 메시지 보내기는 글이 아니라 `sendAction: {recipient, content, sent}` 으로 실어 보낸다 — RAG 서버가 도구 호출과 그 결과로 LLM 에 넣는다 |
| `POST /v1/summarize` | `{fileId, fileName}` → 사용자 Talk 토큰으로 Talk `GET /files/{fileId}` 를 받아 RAG 서버 `/v1/summarize`(파일 이름은 `X-File-Name` 헤더, 퍼센트 인코딩)에 넘기고 SSE 를 흘려보낸다. 거절: 404 `file_not_found`(없거나 그 방 사람이 아님), 413 `file_too_large`(20MB 초과), 503 `talk_unreachable`·`rag_unreachable` |
| `GET /v1/talk/people?q=` | 이름 일부·아이디·"이름(아이디)"로 Talk 사람 찾기. 나는 빠진다 |
| `POST /v1/talk/messages` | `{recipientId, content, requestId}` → 1:1 방을 열고(있으면 그 방) 대화를 보낸다 |
| `GET /v1/health` | 브릿지·RAG(모델·조각 수)·Talk 연결 |

거절은 요청의 `Accept`(SSE 경로는 `text/event-stream`)와 상관없이 늘 JSON `{"code": …}` 으로 온다. 공통 거절:
403 `client_key_invalid`·`user_not_allowed`(허용 목록 밖), 413 `payload_too_large`(본문 상한: 로그인 4KB·요약 4KB·보내기 32KB·대화 512KB),
429 `too_many_attempts`(로그인 시도)·`rate_limited`, 503 `busy`(동시 실행 상한, `Retry-After`).

SSE 이벤트(`POST /v1/chat` · `POST /v1/summarize`):

```
: connected                                     ← 연결 유지용 주석 (요약은 10초마다 : ping 도)
event: sources  data: {"sources":[…], "query":"다시 쓴 검색어"}   ← 대화만. query 는 다시 썼을 때만
event: progress data: {"done":1,"total":3}       ← 요약만. 긴 문서를 나눠 읽는 진행
data: {"delta":"답변 조각"}                      ← 여러 번
event: action   data: {"type":"send_message","recipient":"이서연","content":"…"}   ← 대화만. 답 대신 보내기 요청
event: error    data: {"message":"기록용","code":"no_text"}   ← code 로 앱이 문구를 고른다(message 는 화면에 쓰지 않는다)
event: done     data: {} 또는 {"notFound":true}   ← 답변이 "문서에서 찾을 수 없습니다" 뿐이면 앱이 근거·검색어를 지운다
```

- 요약 실패 code: `unsupported_file`·`no_text`·`encrypted_file`·`broken_file`. 브릿지가 RAG 중계 도중 끊기면 `stream_interrupted` 를 덧붙인다.
- 앱은 `done` 없이 끝난 스트림을 끊긴 것으로 보고, 받은 데까지만 "답변이 중간에 끊겼습니다"로 남긴다.

## 검증

| 실행 | 내용 |
|---|---|
| `./gradlew :bridge:test` | 브릿지 80개 — 접속 키(모든 경로·키 강도·프록시 거친 요청), 본문 상한, 로그인 시도 한도, 동시 실행 상한, AI 계정·자기 자신에게 보내기 거절, 로그인 대행, 토큰 확인, SSE 중계, 한도, RAG 꺼짐, 사람 찾기(이름·아이디·이름(아이디)), 보내기, 로그인 없이 보내기 차단, 보내기 기록(sendAction) 중계, 요약(파일 받기·20MB 경계·Talk/RAG 끊김·파일 이름은 헤더로·로그에 이름 안 남김), SSE 를 바라는 요청의 거절도 JSON |
| `./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test` | 앱 Android(JVM) 160개 · iOS 152개 — 접속 키 싣기, 토큰 암호화 저장·평문 이전, 주소 규칙(평문은 로컬 주소만), 다른 계정 링크의 확인 대화상자, SSE 해석, 마크다운, 대화 묶음·날짜선, 보내기 기록·못 찾음 처리, 실패 분류, 질문 보내기·중지·실패, 보내기 확인·실패 뒤 다시·취소, 기기 DB, 요약 링크 해석·계정 확인·로그인 뒤 이어 가기, 요약 진행·실패 이유·끊긴 답변, HTTP 제한, Koin 구성 |
| `cd rag-server && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/python -m pytest -q` | RAG 219개 — xlsx 직접 흘려 읽기·공유 문자열 상한, 문서·요약 속 지시 무력화(틀 표시 지우기·받는 사람 확인), 형식별 글 뽑기(HWP 는 테스트가 OLE 파일을 직접 만든다), 암호·스캔·깨짐·지원 안 함, 압축 폭탄·글자 상한, 나눠 요약·진행·ping·끊김, `X-File-Name` |
| `maestro --device <기기> test .maestro -e SUMMARY_FILE_ID=… -e SUMMARY_FILE_NAME=…` | 실화면 흐름 3개 × Android·iOS — 추천 질문에 문서로 답하기(근거 칩은 config 로 꺼짐) / Talk 로 메시지 보내기 / 요약 링크(같은 계정·다른 계정 확인 후 로그아웃·취소·엉뚱한 계정으로 로그인). 03 은 test1–test2 1:1 방에 올린 Talk 파일 id 와 URL 인코딩한 이름이 필요하다 |

Android Studio 실행 구성: `테스트 (JVM)`, `테스트 (JVM + iOS 시뮬레이터)`, `브릿지 실행`.

브릿지를 curl 로 부를 때는 `-H "X-Bridge-Key: <bridge.accessKey>"` 를 붙인다(없으면 403).

**메시지가 Talk 에 실제로 들어갔는지**는 받는 사람으로 Talk 서버에 물어 확인한다.

```bash
T2=$(curl -s -X POST localhost:8090/auth/login -H 'Content-Type: application/json' -d '{"id":"test2","password":"1234"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s localhost:8090/rooms -H "Authorization: Bearer $T2"              # test1 과의 방 id 를 찾는다
curl -s "localhost:8090/rooms/<방 id>/messages?limit=5" -H "Authorization: Bearer $T2"
```

- iOS 한글 입력은 자동화가 안 된다. 실화면 흐름은 추천 질문 버튼으로 한글 질문을 보낸다.
- iOS 로그인은 키보드의 return 으로 한다. 작은 iPhone 에서는 자동완성 줄 때문에 로그인 버튼이 키보드에 가릴 수 있어서다.

## 외부 접속 (Cloudflare Tunnel)

바깥(회사 밖·모바일 데이터)에서 쓰려면 **브릿지 하나만** 터널로 연다. RAG 서버·Ollama·Talk 로컬 서버는 열지 않는다.
브릿지는 Talk 로그인으로 사람을 확인하므로, 실제로 열 때는 **README 에 공개된 테스트 계정이 아닌 실제 계정**을 써야 한다.

**임시 주소로 확인** — 계정·도메인 없이 된다. 실행할 때마다 주소가 바뀌고 누구나 닿으므로 짧게 쓰고 닫는다.
Cloudflare 공식 문서상 임시 터널은 **SSE(답변 스트리밍)를 지원하지 않고** 동시 요청이 200개로 제한되며 가용성 보장이 없다.
이 Mac 에서 확인했을 때는 스트리밍이 됐지만 보장되지 않으므로, 접속 확인용으로만 쓰고 운영에는 쓰지 않는다.

```bash
deploy/tunnel/quick.sh            # https://<무작위>.trycloudflare.com 이 찍힌다. 앱 로그인 화면의 "서버" 줄에 넣는다
```

방금 연 주소는 이 Mac 의 DNS 가 "없는 주소"로 잠깐 기억해 curl 이 실패할 수 있다. 1~2분 기다리거나 `sudo dscacheutil -flushcache` 를 한다.

**도메인 없이 고정 주소 — Tailscale Funnel** (이 Mac 에서 확인함). 주소는 `https://<기기 이름>.<tailnet 이름>.ts.net` 이고,
TLS 를 이 Mac 에서 풀어 중계 서버가 내용을 볼 수 없다. 대신 Cloudflare 같은 공격 차단(DDoS·WAF)은 없다.

```bash
brew install --cask tailscale-app                          # Standalone 앱. 비밀번호 입력 → 시스템 확장·VPN 허용 → 로그인은 직접 한다
/Applications/Tailscale.app/Contents/MacOS/Tailscale funnel --bg 8095    # 처음에는 승인 링크가 나온다 — 관리 콘솔에서 승인
/Applications/Tailscale.app/Contents/MacOS/Tailscale funnel status       # 켜졌는지
/Applications/Tailscale.app/Contents/MacOS/Tailscale funnel --https=443 off   # 끄기
```

- 공개 DNS 에 주소가 올라오는 데 보통 10분 안쪽이 걸린다. **넘게 걸리면** 관리 콘솔 Access controls → JSON editor 에서
  `"nodeAttrs"` 블록을 `"nodeAttrs": []` 로 바꿔 저장했다가 원래대로 되돌려 저장하고, Funnel 을 껐다 켠다. 이 Mac 에서는 이것으로 바로 올라왔다
  ([같은 증상 보고](https://github.com/tailscale/tailscale/issues/11849)).
- 이 Mac 도 tailnet 안에 있어 이름이 안쪽 주소(100.x)로 풀린다. 바깥 경로를 확인하려면 공개 DNS 로 찾은 주소로 붙는다:
  `curl --resolve <이름>:443:$(dig +short <이름> @1.1.1.1 | head -1) https://<이름>/v1/health`
- Mac mini 로 옮길 때는 Funnel 을 켜기 전에 관리 콘솔에서 기기 이름을 정해 둔다(이름이 바뀌면 주소와 DNS 등록이 새로 생긴다).
  재부팅 뒤에도 켜지게 Tailscale 의 Launch at login 과 macOS 자동 로그인을 켠다. 옮긴 뒤 MacBook 의 Funnel 은 끈다.

**고정 주소** — Cloudflare 계정과 도메인 하나가 필요하다(계정 만들기와 승인은 직접 한다).

```bash
cloudflared tunnel login                                   # 브라우저에서 도메인을 승인한다
cloudflared tunnel create ai-agent                          # ~/.cloudflared/<UUID>.json 이 생긴다
cloudflared tunnel route dns ai-agent ai.<내도메인>.com
cp deploy/tunnel/config.example.yml deploy/tunnel/config.yml   # UUID·경로·주소를 채운다
cloudflared tunnel --config deploy/tunnel/config.yml run ai-agent
```

상시 운영하려면 Mac mini 의 잠자기를 끄고 `sudo cloudflared service install` 로 부팅 때 터널이 뜨게 한다. 필요하면 Cloudflare Access 로 접근을 한 겹 더 막는다.

## 라이선스

모두 무료이고 상업적 이용을 막는 조항이 없다.

| 구성 요소 | 라이선스 |
|---|---|
| Gemma 4 (`gemma4:26b`) | Apache 2.0 |
| bge-m3 | MIT |
| Ollama | MIT |
| cloudflared | Apache 2.0 |
| Chroma · FastAPI · Uvicorn · httpx | Apache 2.0 · MIT · BSD-3 · BSD-3 |
| Kotlin · Compose Multiplatform · Ktor · Koin · SQLDelight · multiplatform-settings | Apache 2.0 |
| Voyager | MIT |

재배포할 때는 각 라이선스의 저작권 고지를 함께 넣는다. App Store 배포에는 Apple Developer Program 연회비가 따로 든다.
