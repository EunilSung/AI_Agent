# AI_Agent

로컬 LLM 으로 사내 문서에 답하고, 말로 시키면 Talk 메신저에 메시지를 보내는 **AI Agent** 앱(Android · iOS)과 그 서버들.
Talk 메신저(이 저장소 옆 `../Talk`)와 같은 구조·규칙으로 만들었다. 처음 보는 PC 라면 이 문서와 `README.md` 를 먼저 읽는다.

```
[AI Agent 앱] ──▶ [브릿지 :8095] ──▶ [RAG 서버 :8000] ──▶ [Ollama · gemma4:26b + bge-m3]
                       │
                       └──▶ [Talk 서버 :8090] (로그인 확인 · 사람 찾기 · 메시지 보내기)
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
   Talk 의 `:shared` 와 같은 이름으로 옮겨 두었다. Talk 의 API 가 바뀌면 이 파일을 맞춘다. **Talk 저장소는 고치지 않는다.**
6. **사람에게 나가는 동작은 사용자가 확인한 뒤에만 한다.** AI 는 메시지 보내기를 *요청*(`event: action`)만 하고,
   앱이 확인 카드에서 [보내기]를 받은 뒤에 브릿지를 부른다. 확인 없이 보내는 경로를 만들지 않는다.
7. **바깥에 여는 것은 브릿지 하나다.** RAG 서버와 Ollama 는 `127.0.0.1` 에서만 띄운다. 브릿지의 모든 경로(`/v1/health` 제외)는
   Talk 토큰으로 사용자를 확인한다.
8. **실제 주소·계정·기기 식별자·서명 팀은 저장소에 적지 않는다.** 이 저장소는 GitHub 공개 저장소다. 실제 값은 저장소 밖
   `../AI_Agent_info/` 에 모아 두고, 저장소의 코드·문서·커밋 메시지에는 `<기기 이름>.<tailnet 이름>.ts.net` 같은 자리표시만 쓴다.

## 구조

```
composeApp/src/commonMain/kotlin/com/eunilsung/aiagent/
  data/     local(기기 DB·설정) · remote(BridgeClient · SSE 해석) · mapper · repository(*Impl)
  domain/   model · repository(interface) · usecase
  ui/       login · main(탭·태블릿 2단) · conversationlist · chat(말풍선·마크다운·보내기 카드) · setting · uikit · theme
  di/       Modules.kt
shared/     앱 ↔ 브릿지 요청·응답 모델 (com.eunilsung.aiagent.shared.api)
bridge/     Ktor 브릿지 (JDK 17). routes: auth · chat(SSE 중계) · talk(사람 찾기·보내기) · health
rag-server/ Python FastAPI + Chroma. 질문 재작성 → 문서 검색 → Gemma 4 스트리밍, 보내기 도구(send_talk_message)
iosApp/     Xcode 프로젝트 (빌드 단계에서 Gradle 이 ComposeApp 프레임워크를 만든다)
.maestro/   실화면 흐름 — Android·iOS 에 같은 파일을 돌린다
```

- Talk 에서 옮겨 온 것: 테마(`AppColors`)·uikit 일부(상단바·버튼·입력칸·토스트·다이얼로그)·탭·우측 네비게이터·폰 밀어서 닫기,
  키보드 처리(창은 줄지 않고 — Android `adjustNothing`, iOS `OnFocusBehavior.DoNothing` — 화면 맨 아래 `KeyBoardPane` 이
  키보드 높이만큼 밀어 올린다), 대화방 구조(하단바, 대화 목록의 묶음·날짜선·말풍선 옆 시각·최신으로 이동 버튼).
  고칠 때는 Talk 쪽 원본과 어긋나지 않게 한다.
- 대화 기록은 기기 DB 에 로그인한 사람별로 남는다. 스키마를 바꾸면 `Config.Database.NAME` 의 숫자를 올린다.

## 실행

```bash
ollama serve                                                         # gemma4:26b, bge-m3 를 받아 둔다
cd rag-server && .venv/bin/uvicorn app:app --host 127.0.0.1 --port 8000
cd ../Talk && ./gradlew :server:runLocal                             # Talk 로컬 서버 8090 (Talk 저장소)
./gradlew :bridge:run                                                # 브릿지 8095
./gradlew :composeApp:installDebug                                   # Android — 에뮬레이터는 http://10.0.2.2:8095
open iosApp/iosApp.xcodeproj                                         # iOS — 시뮬레이터는 http://localhost:8095
```

로그인은 Talk 계정(`test1`~`test10` / `1234`)이다. 브릿지 주소는 앱의 로그인 화면(서버 주소 줄)이나 설정 탭에서 바꾼다.

## 검증

```bash
./gradlew :bridge:test                                               # 브릿지 (Talk·RAG 는 MockEngine 대역)
./gradlew :composeApp:testDebugUnitTest :composeApp:iosSimulatorArm64Test
maestro --device <에뮬레이터 이름> test .maestro                       # 실화면 흐름 (서버 넷이 떠 있어야 한다)
maestro --device <시뮬레이터 UDID> test .maestro
```

- **동작 확인은 에뮬레이터·시뮬레이터에서 한다.** 실기기는 사용자가 요청했을 때만 쓴다. adb 는 `-s emulator-5554` 처럼 기기를 꼭 고른다.
- **메시지 보내기는 Talk 에 실제로 들어갔는지까지 본다.** 흐름이 끝나면 test2 로 Talk 서버에 로그인해 test1 과의 방 마지막 대화를 읽는다
  (`README.md` 의 검증 절차).
- iOS 한글 입력은 자동화가 안 된다. 실화면 흐름은 추천 질문 버튼으로 한글 질문을 보낸다.
- iOS 로그인은 키보드의 return(완료)으로 한다. 작은 화면에서는 자동완성 줄 때문에 버튼이 늦게 올라온다.
