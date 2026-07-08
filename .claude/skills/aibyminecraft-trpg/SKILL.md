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
- **★프로젝트 문 시나리오 구조 룰렛(ProjectMoonLore)★**: build()가 ★구조를 먼저 추첨★(pickStructure)한 뒤 그 구조를
  중심으로 짜게 한다 — 예전엔 pickAbnormalityBlock가 ★무조건★ 붙어 "환상체 1개 관리"로 100% 붕괴(실측 100/100, 변종·
  특수사건 0). 이제 시대별 가중: 환상체관리(~60% 최다)·변종(ABNO_VARIANT→pickAbnormalityBlock variant=true, 실재성 완화
  예외로 원본기반 변형 허용)·시련(Ordeal)·세피라억제·접대(주최/★손님=관점역전★/기타)·거울던전·도시사건. 에라도 가중
  (pickEra: 로보토미48%>라오루32%>림버스20%). 헤지 '추가 사건'(드물게/매번은아님) 문구 제거(0% 발화 원인). directive()
  0.5)에 구조 준수 규칙. ★"환상체 관리로만"으로 되돌리지 말 것.★
- **★구조 우선(일반 생성기, 스테이지 3+)★**: 예전 typeFirstDirective는 entity.type을 ★랜덤 고정★했으나, 이제
  ScenarioArchetypes.worldRulesBlock가 3+에서 ★첫 후보를 world_rules 구조로 고정★(basic=1~2는 후보 제시 유지)하고,
  typeFirstDirective(GdamGenerator)는 "entity.type을 그 구조에서 도출(먼저 정하지 마라)"로 바뀜. 사용자 지시=
  "ScenarioArchetypes에 맞게 정해져야지 entity.type이 아니라". ★entity.type-first로 되돌리지 말 것★(typeHint 설정 시엔
  기존대로 유형 고정 경로 유지).
- **★괴담 카탈로그(Phase 0 완료, GdamCatalog.java)★**: 173항목 큐레이트 — 통용명·출처(11: 한국/일본/서양/creepypasta/
  scp/cosmic/game/backrooms/internet/real/★sf 신설★)·fame(major28/semi58/minor87)·native_scale[min,max]·vibe(공존 페어링 결)·한줄.
  "::" 구분 텍스트블록+parse(SCP-2521 이름에 '|' 있어 파이프 회피). PM은 ProjectMoonLore 전용이라 제외. all()/bySource()/sources().
  ★아직 소비처 없음 — Phase 1 선정엔진이 인지도·스케일 가중 + 공존 페어링에 쓸 예정.★ 공존은 ★같은 출처끼리만★
  (SCP+cosmic 교차 금지 — 사용자 확정), 그 안에서 vibe로 짝. sf 6하위결(외계·지저·시뮬·음모·기생·AI우주).
- **[예약] 정보 경제 원칙(시나리오 개편 종료 후 검토)**: ①저해상도 힌트가 '사물함이 보인다'처럼 콕 찍어 과다노출 → 다른 것도
  섞어 진짜 저해상도로 ②정보는 얻기 어렵게 + 중요정보는 위험 감수 ③정확 정보=대부분 괴담 쉽게 종결(다회차·강능력 조기종결,
  질질끌기 X) ④잘못된 정보=파멸. 현재 안 지켜짐 — 이전 예약 항목 포함 시나리오 개편 종료 후 착수.
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
- **#231 비용 진단(실측 $17 vs 예측 $12, 40% 초과)**: ★구조는 정상★ — send()(AiManager 1111~)은 system 블록에 ★무조건★ cache_control(1h TTL), GM/NPC/entity 멀티턴은 cacheHistory=true로 마지막 메시지 프리픽스 캐싱. 단가(accumulateUsage 445): 캐시읽기 0.1× · ★캐시쓰기 1.25×(=읽기의 12.5배)★ · 출력 5×(입력 대비). ★중복 규칙 통합은 실효 없음★(베이스 캐시됨, 10줄/842줄≈0.1%). 유력 정체: (1)★캐시쓰기 churn★=1h TTL 만료(느린 인간 턴·세션 중단)·단발게임마다 40K 베이스 1.25× 재생성 / (2)출력 길이(5× 단가, GM 서술 과다) / (3)미캐시 단발호출. ★계측 추가(이번가동 전용, 영구저장·UsageStat 불변)★: accCacheRead/accCacheWrite/accCostOut + `usageDiagLines()` → /trpg status에 순수입력/캐시읽기/캐시쓰기/출력·비용비중·캐시히트% 표시. ★실플레이 1회 후 /trpg status로 정체 규명★(히트% 낮으면 churn / 출력비중 높으면 서술 트림 / 순수입력 크면 미캐시). luckSaves처럼 dead-code 아님 — send 경로 확인됨.
- **injectGmSystem은 이번 턴 전용 버퍼**(AiManager, append-only 제거 완료 873e883): 주입 노트는 gmContext(영구 히스토리)에 안 쌓고 `pendingSystemNotes`(gmLock 보호)에 적재 → callGmAi 전송 스냅샷 후행에만 붙이고 즉시 clear. ★캐싱 유지★: 안정 프리픽스(gmContext)=순수 행동만 → 히스토리 캐싱 온전, 이번 턴 노트만 매 턴 새로 전송. 같은 "[태그]" 노트는 최신으로 교체(leadingTag+removeIf → 상반·중복 누적 방지). flush는 각 줄 '[시스템 주입]' 접두 유지 → 누출 스크럽(stripTags)이 GM 에코 제거(마음의 소리 #213 정합). callGmAiOnce는 단일메시지 문맥이라 flush 안 함(노트는 다음 callGmAi가 소비 — playDiceResult의 [판정 결과] 경로). clearAll/truncate/import 3곳 모두 버퍼도 정리. (예전엔 gmContext 직접 append → 스테이지 내내 stale 누적·토큰↑.) 감사 미이행(보고만·사용자 승인 대기): GM 베이스 매턴 전송(~40K토큰, 단 캐시됨)이라 DICE 2단계·SPR·NPC해결금지·교착 규칙 3~4중 중복이 #231 비용 관련 — 통합 시 절감(고위험). ★프롬프트 태그 예시는 원시 꺾쇠(`<TAG>`)로★ — HTML 이스케이프(`&lt;`)하면 GM이 escape 뱉어 파서 미스(DROP_NOTE 버그 e68c981).
- **결정타: 자동성공 종결 + 실패 치명성**(사용자 설계, 회귀 주의): "핵심행동 완료→종결 판정 저굴림→드래그"의 근본 해법 = ★애초에 불필요한 굴림을 없애는 것★. (A) 자동성공 종결(PB:250): 결정타에도 "충분히 높으면 무판정 자동성공" 원칙이 똑같이 적용 — 관련 능력치가 난이도를 ★명백히 압도★하거나 정석 조건이 ★완전·명백 충족+저위협★이면 굴리지 말고 ★즉시 <CLEAR>★(운 저굴림으로 이긴 판 드래그 금지). '자동성공 금지'는 ★불확실한★ 결정타 굴림 스킵 금지지, 명백한 종결까지 굴리라는 뜻 아님. PB:251 2단계(DICE→다음응답 CLEAR)는 ★굴리는 결정타 한정★ — 무판정 종결이면 곧바로 <CLEAR>. PB:404 '시험'은 과정에서 치르는 것, 다 갖춘 종결을 굴림으로 재봉쇄 금지. ★엔진은 DICE 없는 CLEAR 정상 수락★(onGmResponse 2458 가드는 DICE+CLEAR 동시일 때만 CLEAR 보류) → 순수 프롬프트 정합. (B) 실패 치명성(PB:256 신규 + playDiceResult critHint): 전투·직접위협·자살강행에서 스스로 건 결정타가 ★실패(특히 대실패)★면 부상·후퇴로 무마 말고 ★체력 0=개별 사망까지★ 정당(hp_change 음수). 과보호 금지 — 사전암시 필요한 건 ★전원 몰살(배드엔딩)★뿐, 개별 사망은 즉시 정당(PB:296과 정합). ★엔진 사실★: luckSaves(2847 '위기구제 행운')는 ★정의만 되고 호출 안 됨=dead code★ → LUK 자동구제 미작동(이미 과보호 아님). checkHpCollapse(10441)=hp0 사망·hp1 기절, 클램프로 사망 막지 않음. 잔여 완화(교착방지): PB:282(진행 삭제 금지·2턴 결판)·424~427(단일행동 1회판정)·255(조건↑→dc↓)·워치독(~511-534 제한시각 강제종결).
- **아이템 지급 = charName 정규화 필수**(회귀 주의): GM은 `<ITEM_GRANT>`·STATE_UPDATE의 player에 ★charName★을 넣지만(스키마 지시), ItemManager.processGrant는 p.getName()(계정명)·추적은 pd.name(계정명)으로 매칭 → charName≠계정명이라 게임 중 지급이 100% 실패했었다(로그 전수: 런타임 지급 0, 아이템 이벤트 전부 '시작 소지'). onGmResponse ITEM_GRANT 처리에서 findAnyByName으로 계정명 정규화 후 processGrant/noteHeldItem에 넘긴다. GM 대상명(charName)을 계정명 매칭하는 다른 곳도 같은 버그 의심.
- **영감(SPR) = '지각 해상도'**(중의성 모델 폐기): actorStatGmContext SPR 분기는 7단계 디테일 사다리(1~2 겉모습·3~5 노후탓·6~8 흔적존재·9~11 성질·12~14 형태·15~17 내용·18+ 내용+정체). ★코드 주석은 GM에 안 감★ → 사다리·원칙을 append 문자열에 담아야 함. ★감정·평가 서술 금지★('수상·신경 쓰임·확신') — 물리 사실만, 디테일 수준 자체가 단서(사람은 추론 가능). '해법·이용법'은 어느 영감이든 플레이어 몫. PromptBuilder:226도 동일 모델로 정합.
- **금지어 위협도 = 유사도 비례**: forbiddenSimilarity(0~1)=포함 1.0(정확)·그 미만 편집거리(levenshtein) 최근접 창. 정확→위협도 즉시 90+파국, 비슷한 말(0.6~)→유사도 비례 상승(파국 아님·조용히). 2글자 금지어는 정확만.
- **NPC 말투 4메서드 분리**(관리): npcCorePrompt가 npcEndingHabitBlock(①ending_style)·npcPersonalSpeechBlock(②speech_style)·npcFluencyBlock(③intel 폴백) 중 하나 + npcAgeSpeechBlock(④나이별, 항상) 호출. ageRegisterHint 아래 co-locate.
- **★프롬프트 작업 규약(사용자 상시 지시)★**: (1) 프롬프트(PB:줄 등)를 언급할 땐 ★그 실제 문구도 함께 보여줘라★(사용자가 내용을 알 수 있게). (2) 사용자가 지시한 프롬프트 수정이 ★다른 기존 프롬프트와 충돌하거나 그 프롬프트로 우회·무력화된다면, 그 기존 프롬프트가 무엇인지 원문으로 보여준 뒤★ 처리하라(예: 793만 지우려 했으나 PB:101의 절대금지가 실질 하드코딩이었음 → 101을 먼저 보여주고 완화). 담요식 절대규칙(‘어떤 X도’, ‘절대’, ‘모든 게임’)이 시나리오별 예외를 막고 있는지 항상 점검.
- **NPC 캐리 = 기본 절제·시나리오 게이트**(하드코딩 완화): 예전 PB:101 ‘어떤 NPC도 대신 해결 안 함’ = 모든 게임 담요 금지 → 시나리오가 의도한 캐리 NPC(role_type ‘열쇠’·true_role 조력자/해결사)도 ‘힌트 늦게 주는 NPC’로 격하되던 문제. PB:101을 ‘기본은 절제, 시나리오가 설계하면 더 깊이·결정적으로 도울 수 있다(플레이어 참여 여지·비노출 유지)’로 완화, 중복 PB:793(‘NPC 해결책 통째 금지’ 재진술) 제거. PB:778(다수 NPC 조율)도 ‘해결책 통째 제공 금지’ 담요 문구 → ‘기본 절제(시나리오 지정 캐리·조력 NPC는 예외)’로 축약·완화 완료. role_type 목록=발생원·방어막·제물·열쇠·피해자(제거결과 축)+적대적공조·시스템부품·잘못된가이드(행동 축), 런타임 주입 buildGmPrompt ~11141.
- **불가능 행동 차단 = 대안 제시 금지**(스푼피딩 제거): PB:254·312 = ‘불가능·말 안 되는 행동만 막되 한 줄 이유만, 대안(가능한 길)은 제시하지 말고 플레이어가 직접 생각’. 예전 ‘대신 가능한 길을 제시’(스푼피딩)를 양쪽에서 제거 — 힌트 절제 원칙(PB:105)과 정합.
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
