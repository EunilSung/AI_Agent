# AI_Agent

로컬 LLM 으로 사내 문서에 답하고, 말로 시키면 Talk 메신저에 메시지를 보내는 **AI Agent** 앱(Android · iOS)과 그 서버들.
Talk 메신저(이 저장소 옆 `../Talk`)와 같은 구조·규칙으로 만들었다. 처음 보는 PC 라면 이 문서와 `README.md` 를 먼저 읽는다.

```
[Talk 앱] ──aiagent://summarize──▶ [AI Agent 앱] ──▶ [브릿지 :8095] ──▶ [RAG 서버 :8000] ──▶ [Ollama · gemma4:26b + bge-m3]
                                                          │
                                                          └──▶ [Talk 서버 :8090] (로그인 확인 · 사람 찾기 · 메시지 보내기 · 파일 받기)
```

---

## 규칙

Talk 의 규칙을 그대로 따른다. 다른 점만 덧붙였다.

1. **Clean Architecture 와 MVI 계층을 지킨다.** 새 화면은 기존 화면을 먼저 찾아 같은 모양으로 만든다
   (`XActions`·`XEvent` 는 `XActions.kt`, 상태는 `XUiState.kt`, ViewModel 은 `uiState` 하나와 `events` 만 내놓는다).
   화면은 ViewModel 만, ViewModel 은 유스케이스만 본다. 브릿지 통신은 `data/repository` 의 `*RepositoryImpl` 에서만 한다.
2. **한글을 하드코딩하지 않는다.** `composeResources/values/strings.xml`(영문)과 `values-ko/strings.xml`(한글)에 같은 키로 넣는다.
   LLM 에게 주는 문장(시스템 프롬프트·도구 설명)은 RAG 서버(`rag-server/app.py`)에만 둔다 — 앱은 대화 기록을 데이터로만 보낸다.
3. **`//` 코드 설명 주석을 넣지 않는다.** KDoc(`/** */`)은 한글로 쓴다. 이름은 영어로 쓴다.
4. **기능은 Android 와 iOS 양쪽에 넣고, 양쪽 컴파일·테스트·실화면을 모두 통과시킨다.**
5. **앱과 브릿지의 계약은 `:shared` 를 먼저 고친다.** 브릿지와 Talk 서버 사이의 계약은 `bridge/.../talk/TalkClient.kt` 에
   Talk 의 `:shared` 와 같은 이름으로 옮겨 두었다. Talk 의 API 가 바뀌면 이 파일을 맞춘다. **이 저장소 작업으로 Talk 저장소를
   고치지 않는다.** Talk 쪽이 바뀌어야 하면(예: 파일 대화의 "AI 요약" 메뉴) Talk 저장소의 규칙(브랜치 → 검증 → 머지)으로 따로 한다.
   Talk 앱과의 계약은 링크 `aiagent://summarize?fileId=&fileName=&userId=&userName=` 하나다(`domain/util/SummaryLink.kt`,
   Talk 의 `AiAgentLinkMapper`). 이름을 바꾸면 양쪽을 같이 고친다.
6. **사람에게 나가는 동작은 사용자가 확인한 뒤에만 한다.** AI 는 메시지 보내기를 *요청*(`event: action`)만 하고,
   앱이 확인 카드에서 [보내기]를 받은 뒤에 브릿지를 부른다. 확인 없이 보내는 경로를 만들지 않는다.
7. **바깥에 여는 것은 브릿지 하나이고, 그것도 터널을 거쳐서만 연다.** RAG 서버·Ollama·브릿지(기본 HOST)·Talk 로컬 서버는 모두
   `127.0.0.1` 에서만 받는다(Funnel 은 127.0.0.1:8095 로 붙는다). 브릿지의 모든 경로(`/v1/health` 포함)는 먼저 접속 키
   `X-Bridge-Key`(`local.properties` 의 `bridge.accessKey`, 공백 없는 영문·숫자·기호 32~256자)를 확인하고, `/v1/health` 밖은 Talk 토큰으로 사용자도 확인한다.
   거절은 `routes/ErrorResponses.kt` 의 `respondError` 로 보낸다 — `call.respond(상태, ApiError)` 는 SSE 요청에서 406 이 된다.
   새 경로는 `receiveJsonOrReject` 로 본문 상한을 걸고, LLM 을 부르면 동시 실행 상한(`limit/ConcurrencyLimiter`)을 거친다.
8. **실제 주소·계정·기기 식별자·서명 팀은 저장소에 적지 않는다.** 이 저장소는 GitHub 공개 저장소다. 실제 값은 저장소 밖
   `../AI_Agent_info/` 에 모아 두고, 저장소의 코드·문서·커밋 메시지에는 `<기기 이름>.<tailnet 이름>.ts.net` 같은 자리표시만 쓴다.
   다만 주소를 숨기는 것은 보안 수단이 아니다 — ts.net 이름은 인증서 투명성 로그로 누구나 찾는다. 막는 것은 접속 키와 계정이다.
   테스트 계정 비밀번호(1234)는 공개돼 있으니 바깥에 열 때는 접속 키를 반드시 켜 둔다(키 없이 뜨지 않는다).
9. **공개하는 앱에는 접속 키를 넣지 않는다.** 공개 릴리스 앱은 키도 주소도 없이 빌드하고, 쓰는 사람이 로그인 화면에서 서버 주소와 받은 키를
   넣는다(`data/local/LocalStores.kt` 의 `AccessKeyStore` — 넣을 때의 서버 출처(스킴·호스트·포트)에 묶어 잠가 저장하고 그 출처로만 싣는다).
   그런 빌드는 서버 주소를 넣기 전에는 아무것도 보내지 않는다(빌드 기본 주소는 에뮬레이터용 평문 주소다). HTTP 클라이언트는 리다이렉트를
   따라가지 않는다. `local.properties` 의 키는 개발용 빌드의 기본 키일 뿐이고, 그런 빌드는 바깥 사람에게 주지 않는다.
   GitHub Actions(`.github/workflows`)에는 `AI_AGENT_BRIDGE_KEY`·`AI_AGENT_BRIDGE_URL` 을 비밀값·변수로 넣지 않는다 — CI 와 릴리스가 생성 파일의
   키·주소가 비었는지 확인한다. 릴리스 APK 는 `release` 환경(태그 `v*` 만 허용)의 키로 서명하고, 키가 없으면 릴리스가 멈춘다. 태그 규칙만으로는 쓰기 권한자를
   막지 못하므로 `v*` 태그는 저장소 Ruleset 으로 관리자만 만들게 둔다.

## 구조

```
composeApp/src/commonMain/kotlin/com/eunilsung/aiagent/
  data/     local(기기 DB·설정) · remote(BridgeClient · SSE 해석) · mapper · repository(*Impl)
  domain/   model · repository(interface) · usecase
  ui/       login · main(탭·태블릿 2단) · conversationlist · chat(말풍선·마크다운·보내기 카드) · setting · uikit · theme
  di/       Modules.kt
shared/     앱 ↔ 브릿지 요청·응답 모델 (com.eunilsung.aiagent.shared.api)
bridge/     Ktor 브릿지 (JDK 17). routes: auth · chat(SSE 중계) · summary(Talk 파일 → RAG 요약) · talk(사람 찾기·보내기) · health
rag-server/ Python FastAPI + Chroma. 질문 재작성 → 문서 검색 → Gemma 4 스트리밍, 보내기 도구(send_talk_message)
            extract.py(문서 → 글, 형식별·상한) · summarize.py(나눠 요약·SSE·ping) — LLM 에게 주는 문장은 app.py 에만 둔다
iosApp/     Xcode 프로젝트 (빌드 단계에서 Gradle 이 ComposeApp 프레임워크를 만든다)
.maestro/   실화면 흐름 — Android·iOS 에 같은 파일을 돌린다
```

- Talk 에서 옮겨 온 것: 테마(`AppColors`)·uikit 일부(상단바·버튼·입력칸·토스트·다이얼로그)·탭·우측 네비게이터·폰 밀어서 닫기,
  키보드 처리(창은 줄지 않고 — Android `adjustNothing`, iOS `OnFocusBehavior.DoNothing` — 화면 맨 아래 `KeyBoardPane` 이
  키보드 높이만큼 밀어 올린다), 대화방 구조(하단바, 대화 목록의 묶음·날짜선·말풍선 옆 시각·최신으로 이동 버튼).
  고칠 때는 Talk 쪽 원본과 어긋나지 않게 한다.
- 대화 기록은 기기 DB 에 로그인한 사람별로 남는다. 스키마를 바꾸면 `Config.Database.NAME` 의 숫자를 올리고, 앞 이름을
  `Config.Database.OBSOLETE_NAMES` 에 더한다(앱이 예전 파일을 지운다). 이름이 바뀌면 기기의 대화 기록은 새로 시작한다.
- 토큰·사용자 정보·접속 키는 Talk 와 같은 방식으로 Keystore(Android)·Keychain(iOS) 키로 잠가 저장한다(`data/local/LocalSecret*`).
- 앱의 평문 HTTP 는 로컬 주소(10.0.2.2·localhost·127.0.0.1)에만 열려 있다(Android `network_security_config`, iOS
  `NSAllowsLocalNetworking`, 그리고 앱의 주소 규칙 `BridgeAddress` — 입력·저장·빌드 기본 주소 모두 거친다). 실기기는 https 터널 주소를 쓴다.
- 링크는 플랫폼 진입점(Android `MainActivity` 의 intent-filter·`onNewIntent`, iOS `iOSApp.swift` 의 `onOpenURL`)이
  `IncomingLinks` 에 넣고, `MainViewModel` 이 계정을 맞춘 뒤 대화를 연다. 대기 중인 요약은 Voyager Screen 인자가 아니라
  저장소에 대화 id 로 걸어 두고 `ChatViewModel` 이 꺼낸다(Android 상태 저장 직렬화를 피한다).
- SSE `error` 의 `message` 는 기록용이다. 화면 문구는 앱이 `code` 로 고른다. 브릿지의 거절은 `Accept` 와 상관없이 JSON 이다
  (`routes/ErrorResponses.kt`) — `call.respond(상태, ApiError)` 를 쓰면 SSE 요청에서 406 으로 바뀐다.

## 실행

```bash
ollama serve                                                         # gemma4:26b, bge-m3 를 받아 둔다
cd rag-server && .venv/bin/uvicorn app:app --host 127.0.0.1 --port 8000
cd ../Talk && ./gradlew :server:runLocal                             # Talk 로컬 서버 8090 (Talk 저장소)
./gradlew :bridge:run                                                # 브릿지 127.0.0.1:8095 (local.properties 의 bridge.accessKey)
./gradlew :composeApp:installDebug                                   # Android — 에뮬레이터는 http://10.0.2.2:8095
open iosApp/iosApp.xcodeproj                                         # iOS — 시뮬레이터는 http://localhost:8095
```

로그인은 Talk 계정(`test1`~`test10` / `1234`)이다. 브릿지 주소는 앱의 로그인 화면(서버 주소 줄)이나 설정 탭에서 바꾼다.

## 검증

```bash
./gradlew :bridge:test                                               # 브릿지 (Talk·RAG 는 MockEngine 대역)
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test
cd rag-server && .venv/bin/python -m pytest -q                       # RAG (LLM 은 대역, 시험 문서는 테스트가 만든다)
maestro --device <에뮬레이터 이름> test .maestro -e SUMMARY_FILE_ID=<id> -e SUMMARY_FILE_NAME=<인코딩한 이름>   # 서버 넷이 떠 있어야 한다
maestro --device <시뮬레이터 UDID> test .maestro -e SUMMARY_FILE_ID=<id> -e SUMMARY_FILE_NAME=<인코딩한 이름>
```

- **동작 확인은 에뮬레이터·시뮬레이터에서 한다.** 실기기는 사용자가 요청했을 때만 쓴다. adb 는 `-s emulator-5554` 처럼 기기를 꼭 고른다.
- **메시지 보내기는 Talk 에 실제로 들어갔는지까지 본다.** 흐름이 끝나면 test2 로 Talk 서버에 로그인해 test1 과의 방 마지막 대화를 읽는다
  (`README.md` 의 "테스트" 절).
- iOS 한글 입력은 자동화가 안 된다. 실화면 흐름은 추천 질문 버튼으로 한글 질문을 보낸다.
- 요약 흐름(03)은 test1–test2 1:1 방에 올린 Talk 파일이 필요하다. 상대(test2)가 REST 로 파일을 올리고(`POST /rooms/{방}/files?name=`
  → `POST /rooms/{방}/messages` kind=file) 그 id 를 넘긴다. iOS 는 팝업이 떠 있는 동안 뒤 화면을 계층에서 숨기므로 팝업을 닫은 뒤 본다.
- Talk → AI Agent 앱 간 실행은 Talk 의 실화면 흐름 `06_ai_summary` 가 본다(기기에 AI Agent 가 있으면 열리는지, 없으면 설치 안내).
- iOS 로그인은 키보드의 return(완료)으로 한다. 작은 화면에서는 자동완성 줄 때문에 버튼이 늦게 올라온다.
