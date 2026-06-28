# run2/iter04 — 평가·에필로그·컨텍스트 압축의 계정명 노출 (F8, 원래 버그의 진짜 뿌리)

아이템/게이트·정체차용·NPC 자율턴은 코드 대조로 정상 확인 후, 평가 경로를 추적하다
**사용자가 원래 신고한 "에필로그에 소흐/르니키(계정명)가 뜬다"의 진짜 뿌리**를 발견.

## 정상 확인 (수정 불필요)
- 아이템 적용 `applyItemUse`: 모든 필드 has()/isJsonNull() 가드 + try/catch. 크래시 없음.
- `resolveItemInstance`: 모호 부분일치는 null(오매칭 방지). 게이트 `gatePassReason`: null-safe.
- 정체차용 `deliverImpersonatedReply`·`deliverDirectMessage`: 전부 commDisplayName(=gmDisplayName). 누수 없음.
- NPC 자율턴 `fireNpcAiForTurn`: zone 필터 로그·null 가드·엿보기 동일 zone 한정.

## F8 (높음·원래 버그 뿌리) — 평가/압축이 계정명을 AI·화면에 노출

세 갈래로 계정명이 샜다:

1. **평가 헤더 화면 출력** (`parseEvaluation` ~3864): `who = pName`(= AI가 돌려준
   player 필드 = 계정명, 프롬프트가 "괄호 앞 부분=계정명을 쓰라"고 지시)을 그대로
   `§f§l{who}` 로 **전원에게 broadcast**. 주석 예시까지 `help(한소율 프리랜서)`.
   → 최종 평가 화면이 계정명을 그대로 노출. (#32가 '이름·역할 표시'를 계정명으로 구현)

2. **평가 입력 로그** (`buildFullEvalLog`): `e.toLogString()`을 ★resolveDisplayName
   없이★ 직접 사용. action 로그는 `TurnManager:58 state.log("action", pd.name,…)`,
   clue/근처/전체발신도 계정명 저장 → 평가 AI가 `[heIp12] …`를 보고 desc에 echo 가능.

3. **컨텍스트 압축** (`ContextCompressor` 44·72): 동일하게 `toLogString()` 직접 사용
   → 압축 요약 AI가 계정명을 보고, 요약이 GM 컨텍스트로 주입돼 GM 서술로 새어나갈 수 있음.

`buildTurnInput`/`buildEntityLog`는 `resolveDisplayName(e.player)`로 출력 시 변환하는데,
평가·압축 두 소비자만 이 변환을 빠뜨린 게 근본 원인(저장은 계정명=고유키, 출력서 변환이 설계).

### 조치 (저장 안 건드림 — exact-match 로직 보존, 출력층에서만 변환)
- `parseEvaluation` 헤더: `who = epd.gmDisplayName()`(+캐릭터명 있을 때만 직업 괄호). pName은
  grades/growth 맵 ★내부 키★로만 유지(findByName 매핑 보존).
- 평가 프롬프트: "player는 내부 식별자일 뿐, role_label·desc에 계정/영문 ID 금지" 가드 추가.
- `EventLogEntry.toLogString(UnaryOperator<String> resolver)` 오버로드 신설.
- `GameStateManager.resolveDisplayName` public 전환, `buildFullEvalLog`가 `this::resolveDisplayName`로 렌더.
- `ContextCompressor` 2곳 `e -> e.toLogString(state::resolveDisplayName)`.
에필로그(`concludeWithReveal`)는 이미 "계정 ID 금지" 지시 + 입력 로그가 gmDisplayName이라
이중 안전. 이제 평가·압축 AI는 계정명을 ★볼 일조차 없어짐★(지시 의존이 아니라 구조적 차단).

## 결론
F8 1건(원래 신고 버그의 뿌리) 발견·수정. 4개 파일 brace=0. 이로써 계정명이 서술·통신·
특성효과·평가·에필로그·압축 그 어느 AI/화면 경로로도 나가지 않는다(구조적 보장).
