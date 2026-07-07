---
name: aibyminecraft-trpg
description: >-
  AiByMinecraft 괴담 TRPG(Paper 플러그인, Project Moon/로보토미 톤) 개발 규약·검증 절차·
  아키텍처 지도. 이 저장소의 Java 게임 코드(heipsys.trpg.*)나 로그 뷰어(log-viewer.html)를
  수정/디버그/리뷰할 때, 또는 .gdam 시나리오·NPC·통신·턴 시스템을 다룰 때 사용. 컴파일
  불가 환경이라 커밋 전 검증(브레이스 검사 + node --check + 크로미엄 렌더) 절차가 핵심.
---

# AiByMinecraft 괴담 TRPG — 개발 규약·검증

한국어 마인크래프트(Paper 1.21.7) 괴담 TRPG 호러 플러그인. AI(GM/NPC/괴담)가 시나리오
(.gdam)를 굴리고, 플레이는 log-viewer.html로 시점별 재생·감사한다.

## ★ 커밋 전 검증 (가장 중요 — 컴파일 불가 환경)
빌드/컴파일이 안 되므로 아래 3단으로 검증한다. **커밋 전 반드시.**

1. **브레이스 검사** (Java·HTML 공통 1차):
   `python .claude/skills/aibyminecraft-trpg/tools/bracecheck.py <file>` → `OK 0/0/0` 이어야 함.
   - Java에서 정규식-무관하므로 신뢰. **주의**: HTML 안의 JS 정규식 리터럴(`/\(([^)]*)\)/` 등)은
     오탐(MISMATCH)을 낼 수 있음 → 그 줄이 편집 대상이 아니고 아래 node --check가 통과하면 무시.
2. **JS 문법** (log-viewer.html 수정 시): `<script>` 추출 후 `node --check`. → node가 최종 판정.
3. **크로미엄 렌더** (뷰어 동작 검증): 헤드리스로 로그 주입해 실제 결과 확인.
   - 편의 도구: `python .claude/skills/aibyminecraft-trpg/tools/viewer_verify.py --viewer AiByMinecraft/src/main/resources/log-viewer.html --log <a.jsonl> [--scenario <s.json>] [--probe <probe.js>]`
   - 크롬 바이너리: `/opt/pw-browsers/chromium_headless_shell-*/chrome-linux/headless_shell`
     (플래그: `--headless --no-sandbox --disable-gpu --virtual-time-budget=6000 --run-all-compositor-stages-before-draw --dump-dom`)
   - 뷰어 진입점 `loadLogText(name, text)`; 시나리오는 `SCENARIO=` 주입; 시점검사는 `curVP` + `visibleTo(e,vp)`/`filtered()`.

## 커밋·브랜치 규약
- 개발 브랜치: `claude/optimistic-hamilton-jw2ayv` (지정된 경우 그 브랜치). `git push -u origin <branch>`.
- 커밋 메시지: **한국어**, 첫 줄 요약("게임:"/"뷰어:"/"생성기:" 접두), 본문에 원인·수정·검증.
- 푸터(커밋): `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` + `Claude-Session: ...`.
- 모델 식별자(claude-opus-4-8 등)를 커밋·PR·코드·주석에 넣지 말 것(채팅 답변에만).
- PR은 사용자가 명시 요청할 때만.

## 아키텍처 지도 (AiByMinecraft/src/main/java/heipsys/)
- **trpg/TRPGGameManager.java** (초대형): 게임 흐름 허브. handleChat/handleAction, 프롬프트 조립
  (npcCorePrompt/npcFeatureBlocks/buildNpcSystemPrompt/buildGmSystemPrompt), 통신 라우팅
  (handleDirectComm/broadcastToKnownContacts/deliverPlayerBroadcast/handleNpcDirectComm),
  주사위(playDiceResult), 상태(꼭두각시/기절), 지도(openMapSelector), 소통수단(#177), 능력.
- **trpg/PromptBuilder.java**: GM 시스템 프롬프트 상수(판정·아이템·기절/홀림·zones·통신 규칙 등).
- **trpg/GdamGenerator.java**: .gdam 시나리오 생성 프롬프트·스키마(zones/npcs/roles/timeline/constraints).
- **trpg/AiManager.java**: AI 호출(callGmAi/callGmAiOnce/callNpcAi/callAssistant), 태그 파싱(parseTag/parseDiceTag/…), injectGmSystem.
- **trpg/DialogManager.java**: Paper Dialog UI(시작설정·유형선택·기록·지도·소통수단 등).
- **trpg/MapManager.java**: 구역/약도(zones·area·connections·distances), MapView 렌더(시점별 visitedZones 게이팅), knownAreaNames/sameArea.
- **trpg/GameLogger.java**: 이중 로그(.txt + .events.jsonl) — logVital/logItem/logComm/logMove/logAbilityResult/section.
- **trpg/model/PlayerData.java**: 스탯(hp[]/san[]/str/cha/luk/spr, 1~20 스케일 5=평균), status, 소통수단 선언, zone/visitedZones.
- **ChatListener.java**: 채팅·우클릭(정보/기록/지도/통신기기)·드롭/설치 차단.
- **trpg/resources/log-viewer.html**: 단일 파일 뷰어(파싱 normalize → EVENTS, 시점 visibleTo, 라이브 패널, 구역 지도 SVG, .gdam 복호화).

## 규칙·도메인 체크리스트 (회귀 방지)
- **메타 노출 금지**: 계정명·영문 아이템 id(smartphone 등)·내부 스키마 용어(role_id/zone_id)를
  플레이어 서술·로그에 노출 금지. 표시명은 charName(직업), 아이템은 한국어명.
- **★계정명(pd.name)은 프롬프트에 절대 금지★**(서버·로그·메타화면 전용): AI로 가는 모든 문자열은
  gmDisplayName()(=charName→직업→"이름 모를 인물") 또는 resolveDisplayName()만 쓴다. 안전 경로(검증됨):
  buildTurnInput(폴백도 익명)·buildEntityLog·buildFullEvalLog/buildCampaignEvalLog(resolveDisplayName)·GM
  등장인물명단(charName)·능력 injectGmSystem(gmDisplayName). 평가는 프롬프트엔 gmDisplayName 보내고
  parseEvaluation이 grades/growth 키를 epd.name으로 ★정규화★해 보상 귀속을 유지(다운스트림 계정명 키 불변).
  새 프롬프트 작성 시 pd.name/actor.getName()/player.getName() 직접 삽입 금지.
- **스포일러 금지**: 미발견 구역·괴담 정체/약점 사전 노출 금지(지도 다이얼로그·프롤로그 등).
- **뷰어 시점 가시성 = "실제로 닿은 것만"**: `visibleTo` — 본인/지목수신(to)/system은 표시,
  근처·방송은 같은 구역만, '전체(전역)'는 플레이어만(NPC 미표시). GM/시스템 안내방송은 플레이어 전원.
- **통신 모델**: @전체=아는 번호 플레이어만(NPC 미수신·비용). @NPC 1:1은 가능. 대면은 소통수단
  선언(#177) 우선(음성/필담), 없으면 소리위험 시 자동 필담. 위치 불명 NPC는 대면 취급.
- **괴담 이름(친숙 모드)**: 실존 괴담은 한국 통용 명칭 우선(빨간마스크(口裂け女) 등), 의역 금지,
  확신 없으면 원어(발음). 회차 시드 번호를 이름에 넣지 말 것.
- **모델 티어**: GM=medium(sonnet), NPC=mini(haiku). 정밀 1회성은 callAssistantHiFi. 정밀 대형
  설계는 사용자 승인 하에 Fable5(claude-fable-5) 다중에이전트 Workflow("울트라코드").

## ★ 환경·도구 제약 (효율 메모 — 꼭 확인)
- **Workflow(다중에이전트 울트라코드)는 헤드리스 자율 실행에서 launch 실패**: 권한 스트림이 닫혀 1-에이전트
  프로브도 못 뜬다("Tool permission stream closed"). 토큰 문제가 아니라 구조적 → 재시도해도 동일.
- **★그러나 Agent 도구(Task 서브에이전트)는 이 헤드리스 환경에서도 정상 작동★ — Workflow와 별개 서브시스템.**
  `Agent(subagent_type:"general-purpose", model:"fable")`로 ★Fable5 단일 에이전트★ 감사·설계가 실제로 뜬다(검증됨).
  즉 "울트라코드(Workflow)는 막혀도 Fable5 모델 작업은 Agent 도구로 가능". NPC 프롬프트 감사 등은 이 경로를
  먼저 쓰고(모델 지정), 그마저 막힐 때만 인라인(단일 모델). 다중에이전트 팬아웃이 꼭 필요하면 Agent를 여러 번 호출.
- **모델 라우팅(사용자 지시)**: NPC 프롬프트 작업=Fable5 울트라코드 · 그 외=Opus 4.8 · 턴/맵 설계=Opus 4.8
  울트라코드. NPC 설계 목표 = ★저급 모델도(약간 어색해도) 원활 작동 + 상급 모델이면 영화처럼★. 스킬은
  상시 갱신(효율 될 만한 지식은 항상 등록).

## 최근 추가된 아키텍처 사실 (회귀 방지)
- **자동진행 = GM위임 아닌 코드 결정(전지성 차단)**: 자동진행 경로 4개 중 GM을 호출하는 건 `maybeAccelerateIdle`
  하나뿐 — 나머지(advanceRoundAfterAllActed / busyClockJumpIfAllBusy / summonAllFree / 전원무력 워치독)는
  ★GM콜 0★(순수 시계·카운터 연산)이라 프로즈를 안 만들어 미발견 정보 누출이 원천 불가. maybeAccelerateIdle은
  이제 ★코드가 스킵 판정★(위협/분노 70+ 또는 endEventFired=급박→스킵안함, 아니면 nextDueEventMinute 직전까지만
  스킵)하고 GM엔 '흐른 시간의 앰비언트'만 시킨다(미발견 단서·정체/약점·미도달 구역 언급 금지, TIME_SKIP 태그 금지).
  ★"다음 사건으로 넘겨라"류로 되돌리지 말 것★ — 그게 전지적 자동진행의 원인이었다.
- **GM위임→하드코딩(비용 #231)**: ★A1/A3/A4/A5 구현 완료★ — A1 무행동가속 스킵 코드결정+전지성 가드(dd8f655),
  A3/A4 전투 사건(combat:true) 자동 소집·완급(GameStateManager.combatEventFired→consumeCombatEventFired →
  TRPGGameManager.reactToFiredCombat, busyClockJumpIfAllBusy·maybeAccelerateIdle 직후 호출, 8b0288c), A5 마감 후
  결말 강제 백스톱(무력화 워치독 '행동가능' 분기, endHangTicks; is_end 결과타입 없어 위협도로 생존/파국 근사, 26a5b1e).
  ★잔여 A2★: `extractAndStoreInfo`(:6344) 서술 있는 매 턴 보조모델 2차 콜 → 메인 GM `<CLUE>` 태그 흡수 or 사전필터
  게이팅(GM 출력계약 변경이라 리스크·검증량 큼 — 사용자 확인 후). ※A5 후속: is_end 사건에 결말 타입(survival/doom)
  스키마 필드 추가하면 위협도 근사 대신 정확 판정 가능. 이미 잘 하드코딩된 것: 주사위 판정(:11360 roll·성패 코드)·
  통신 파이프라인(:8343)·비동기 턴진행·GM 태그검증(resolveZoneId/capGradeByThreat).
- **★주사위 2단계 불변식(회귀 주의)★**: 판정은 ★시도 응답(<DICE>만, 결말 없음) → 시스템 굴림([판정 결과] 주입) →
  다음 응답에서 결과 서술★의 2단계다. onGmResponse에서 <CLEAR>가 <DICE>와 ★같은 응답★에 오면 CLEAR를 그 턴에
  처리하지 말 것(굴림보다 먼저 처리돼 return되면 주사위가 아예 안 굴러가고 무판정 종료 = '주사위가 끝에 굴러 영향
  없음' 버그, 411fb3b). 코드가 clearTag를 보류→아래로 흘려 <DICE> 굴림 수행 + '판정 먼저' 주입. 프롬프트(PromptBuilder
  250·260·280)도 ①DICE+CLEAR·결말 동일응답 금지 ②'굴림 후 결과 다음 응답'은 교착 아님(280 anti-교착과 상충 해소).
  ★"결정타는 그 턴에 결판"을 "결말까지 한 응답에 써라"로 되돌리지 말 것★.
- **행동 소요시간(DUR) 폴백**: 가변/비동기 턴(turnMode≥1)에서 GM이 <DUR> 누락 시 durEff는 ★min(minutesPerTurn,3)분★
  (`DUR_MISSING_MIN`, TRPGGameManager ~2579) — 예전 minutesPerTurn(15~20) 통째 폴백이 '사소한 행동에 20분씩' 소모의
  원인(a838e65). 폴백은 turnMode 1(advanceActionClock)·2(busy)에서만 쓰이고 고정턴엔 무관. 큰 행동은 GM이 DUR 명시.
- **스코어보드 '내 차례' 줄**: 공포 단계(!isDailyPhase)에 turnStatusLine 표시 — 가변/고정="▶ 지금 행동 가능",
  비동기 busy="다음 행동까지 N분"(busyUntilMin−clock), 행동불가=사유. 미발견 사건시각 노출 없이 행동가능 여부만(754a5a4).
  ※GameStateManager.isDailyPhase()는 한 번만 정의(#208 커밋 f05b648이 중복 추가→컴파일오류였음, 94eb13b에서 제거).
- **꼭두각시 상태머신**(TRPGGameManager 턴루프 ~8931 + san_change ~2271): puppetRecoveryTurns(>0 관전
  /-1 완전조종 heal전용/0 중간), puppetTotalTurns(누적→상한 시 강제완전회복), puppetGraceTurns(회복 직후
  재조종 유예 — 낮은 SAN이어도 재조종 트리거 차단). 정상복귀 시 total 리셋.
- **통신 변조 게이트**: entityInterferes(modality)=entityTampers{Voice,Written,Signal,Electronic,Psychic}
  (엔티티 name/type/rules/ai_context 키워드 매칭 — '소리·울림' 광의어는 오탐하니 좁게). tamperText는 핵심어·
  숫자 뒤집기 우선, 없으면 신호끊김(…). 물리형 괴담이 통신 변조하면 게이트 오탐 의심.
- **소통수단(#177)**: 기본 4종은 GM 호출 없이 필드로 즉시 판정(applyCommMethodLocal). 새 수단 GM 판정은 #180.
- **비용 기법 현황(참조본 zip 대조 결론)**: 우리는 이미 프롬프트 캐싱(시스템+히스토리 프리픽스 cache_control·1h TTL)·적응형 effort(output_config)·모델 티어·자동탐지·JsonSalvage(절단JSON 로컬복구, HEAD)를 갖춰 Claude 기준 참조본과 동등 이상. 참조본의 절감은 저가 제공사(MiMo/Cerebras) 전환+reasoning suffix가 실체 — Claude엔 무의미. 다중프로바이더는 열면 `AICraft.java` 한 곳(providerFor/modelName/applyReasoningEffort). ★비용 아닌 품질 갭★: 참조본은 히스토리 system-role을 Claude GM에 `[시스템 지침]`으로 전달, 우리는 Claude 경로에서 드롭(AiManager send Claude 분기) — 필요 시 REF 1766-1781 병합루프 이식.
- **injectGmSystem은 append-only**(AiManager): 주입 노트는 스테이지 내내 gmContext에 누적, per-turn clear 없음(clearAll/rewind에서만 초기화). 같은 태그 상반 노트가 쌓이면 GM이 둘 다 인지 → 태그 재사용 시 의미 통일 필수(예: '[통신 채널 불안정]' 이중의미 버그 e68c981). 감사 미이행(보고만): GM 베이스 매턴 전송(~40K토큰)이라 DICE 2단계·SPR·NPC해결금지·교착 규칙 3~4중 중복이 #231 비용 직결 — 통합 시 토큰 절감(고위험). ★프롬프트 태그 예시는 원시 꺾쇠(`<TAG>`)로★ — HTML 이스케이프(`&lt;`)하면 GM이 escape 뱉어 파서 미스(DROP_NOTE 버그 e68c981).
- **은밀 대화(밀담) 능력(#234, one_way_call 4인자화)**: effect_type=`one_way_call`에 4파라미터 —
  `uses`(0=무제한), `detect`(0=괴담 감지불가[은밀·기본]/1=감지가능), `direction`(0=일방 전언/1=청취 채널/2=대화창 양방향),
  `chars`(0=무제한 글자수). 코스트=방향(일방1·청취3·대화창5)+은밀(+2)+무제한(+2)+긴글자(+1), S+예시=대화창·은밀·무제한=10(S).
  런타임(TRPGGameManager): direction0은 다이얼로그 일방 전송(activateOneWaySend, '전체' 지원), direction1/2는
  스테이지 채널 개설(secretChannels: owner→SecretChannel) → 멤버는 채팅 앞 `!`로 은밀 송수신(handleGameChat 최상단 훅
  handleSecretChannelChat, 채널 없으면 false→일반채팅 폴백). deliverSecretText가 logComm(kind=whisper,via=밀담) +
  detect면 noteEntityIntel·GM주입. secretChannels는 스테이지 리셋 시 clear. reduceOneParam에 'direction' 추가(초과 시 대화창→청취→일방 강등).
- **뷰어 재생**: buildQueue→queue/qi, step()↔renderQueueItem(instant), seekTo(구간 슬라이더), evHtmlSplit
  (전체·시점 공통 — GM서술 내 [이름]대사 분리), headHtml 'other'클래스(타인=우측정렬), mapZoom(지도 확대),
  #infoResize(정보창 폭·--ifs 글씨 스케일).
- **NPC 시점 로깅(#188)**: 자율 NPC 호출은 <THOUGHT>를 ★항상★ 요청 → GameLogger.logNpcThought
  (kind=thought, actor=NPC — 그 NPC 시점·전체뷰에만, 플레이어엔 비노출) + 게임 내 엿보기 공개는 별개(같은
  구역 엿보기 특성만). logNpcLocationIfChanged(npcLoggedZone 추적, 바뀔 때만 logMove) → 뷰어 zoneAtSeq가
  NPC 위치 인지 = 라이브 패널 '위치' + 근처/방송 가시성. 뷰어 kind=thought 배지 💭·`.k-thought`(흐린 보라·이탤릭).
- **행운 보정 수명(#176)**: pendingLuckModifier는 ★다음 실제 판정(주사위)까지 유지★ — 행동 처리 땐 get으로
  GM문맥만 알리고, playDiceResult 굴림 시점에서만 remove(1회 소비). 취약한 중간 스태시 pendingDiceLuck 제거됨.
  (예전엔 판정 없이 서술만 된 행동에서 보정이 증발.) 세 리셋 블록이 pendingLuckModifier.clear().

## 진행 중 설계(예약) — 착수 시 이 맥락으로
- **턴(#151)·맵통신(#180) — ★완전 설계 완료★**: `docs/design/turn-map-design.md`(구현 승인 대기). 요지:
  행동별 소요시간(<DUR>)+busyUntil 비동기 턴, 무행동 스킵, 완급, 즉시소집(<SUMMON>), 전원행동불능
  자동종료(#2), 통신 4단계 파이프라인+@전체 지연큐(#109). §6 위험부분(비동기 루프 재구조화·자동종료
  오종료·@전체 비용)은 승인 후. 착수는 §5 단계순(저위험 시계/자동종료부터).
- **턴 개편(#151) 원개념**: 고정 턴 폐기 → 가변 시간. GM이 시계 운전(행동 소요시간·완급 매김), 사건 발생
  시 즉시 소집, busy 중에도 GM 짧은 서술·피격·통화 가능, 이동은 zones.distances 참고, busy 중
  피격 소집 시 긴 행동 중단/재개는 GM 결정. 무행동 자동 스킵(#163) 포함.
- **NPC 발화 3층화(#179)**: idle 못닿는 NPC=값싼 정적 예정 주입(완료) / 사건 발생 시 관련 NPC
  1회 호출(개입 창발) / 능동 비트(가끔 라운드로빈 1명, 이동·콜드콜로 상황 변경). sparse 트리거·상한.
- **맵·통신 게이팅(#180)**: 통신 1건 판단 파이프라인 = 차단→지연→수집→왜곡(구역/통로/매체·괴담
  수집범위별, 코드 확실→코드·애매→GM). @전체 지연큐(#109): 코드 선제외→애매 후보만 GM→비용
  초과 시 전부 코드.
- **speech_style(완료, #178)**: NPC 말투 = 서술형 한 문장(critical 전용), 주사위 유창도 대체.
  원안 6필드는 소비 엔진 부재로 기각. 실플레이 3사 A/B 검증은 미실행(추후).
- **NPC 프롬프트 감사(완료, #186 — Fable5 Agent)**: 말버릇 3불릿 분해(어미=일관/필러=가끔·연속금지,
  없던 어미 생성 금지), 대화 정보흐림은 진상·해법 한정(일상 대답 흐림 금지), callNpcAi 4-arg 오버로드
  (자율="관측된 행동 로그:"/대화=머리말 그대로 — 3인칭·보고체 누출 완화), 직접 대화 <NPC_LEARN> 소비,
  머리말 등급 봉인, knowledgeConfidence 확신40·소문15, 결정적 순간 6문장 허용. ★남은 감사(로그 관측 후)★:
  응답순서 예시 반말 레지스터, ★기호 에코, 단일주체 엔티티 AI(#119) 톤이 npcCorePrompt와 다른 문법인지 별도 감사.
