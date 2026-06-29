# 괴담 TRPG 프롬프트 자가개선 루프 — 종합 검증 보고서 (SYNTHESIS)

작성 기준: 2026-06-26, iter01~14 완료 후  
검증관: 14회차 종합 검증관 에이전트  
대상: prompt_patches.md (P1~P43 + CODE-1~10) × 실제 .java 소스 매핑

---

## 1. 패치 적용 위치 매핑 표 (P1~P43)

| 패치ID | 대상 프롬프트 블록 (파일·상수/메서드명) | 한 줄 요지 | severity |
|--------|--------------------------------------|-----------|----------|
| P1 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 클리어 판정 섹션 (line ~304) + `runScenarioEvaluation` 평가 프롬프트 (line ~3074) | 재도전 횟수 자체는 등급을 낮추지 않는다 | high |
| P2 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` world_rules 설계부 (line ~223) + `buildGmPrompt` world_rules hidden 주입 (line ~4444) | hidden 규칙 — 현상만 보여주고 GM이 메커니즘을 확정·요약하지 않는다 | med |
| P3 | `TRPGGameManager.java` — `buildGmPrompt` 타임라인 시계 주입부 (line ~4519~4524) | 평온 구간 TIME_SKIP 적극 사용, 급박 구간 분·초 정밀도 | med |
| P4 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` constraints.notes 설계부 (line ~251) | 소음 트리거 괴담 constraints.notes에 통화 음성 = 의도적 소음 동등 취급 명시 | med |
| P5 | `TRPGGameManager.java` — `buildNpcSystemPrompt` schedule 출력부 (line ~3728~3741) | will=반응 발동 타이밍: condition 실제 발생 직후 다음 1턴 안에만 | med |
| P6 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 판정 시스템 섹션 | 협력 판정 공식: 주도자 d20 + 보조 1인당 +2(최대 +6) | high |
| P7 | `TRPGGameManager.java` — `buildGmPrompt` 난이도/room 분기 (line ~4427~4428, 4576) + `onClearEnding` (line ~2969) | room≤2 생존판정도 진출 가능 → CLEAR reason에 자동 명시 | low |
| P8 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` clues 설계부 (line ~430) | clues에 mislead 1~2개 필수 포함 | med |
| P9 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` npc_schedules 설계부 (line ~321~348) + `buildNpcSystemPrompt` schedule 주입 (line ~3728) | schedule 항목에 선택적 duration_turns 필드 | med |
| P10 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` clues 설계부 (line ~430) + `GM_SYSTEM_BASE` mislead 운용 | mislead: 진짜 단서 2개+ 확보 후 자연스럽게 오답 드러남, 처음부터 거짓 티 금지 | med |
| P11 | `TRPGGameManager.java` — `buildNpcSystemPrompt` schedule 주입 (line ~3728) + `GdamGenerator.java` GDAM schedule 스키마 | schedule에 선택적 after_duration("복귀"/"대기"/"도주") 필드 + 종료 후 처리 | med |
| P12 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 클리어 판정 섹션 (line ~304~334) | 해결/생존 경계: collapse 완전 충족 + 위협권 전원 탈출/생존=해결, 부분=생존, 모호=생존 쪽 | high |
| P13 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` constraints 스키마 (line ~243~261) + `TRPGGameManager.buildGmPrompt` 제약 주입 (line ~4466~4486) | constraints에 gated_zones[] 배열 추가, 장비·인증 잠금 구역 처리 기준 | high |
| P14 | `TRPGGameManager.java` — `buildGmPrompt` comms_monitored 주입 (line ~4480~4482) | comms_monitored=true 시 사용 1회마다 오염도+1 또는 위협단계 1상승 | med |
| P15 | `TRPGGameManager.java` — `buildGmPrompt` zones 주입부 (line 4423 이후 zone 관련) | zones 6개 이상 또는 내셔널/글로벌이면 구역 접근권 계층 주입 | med |
| P16 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 클리어/재시도 판정 | 스테이지 내 리트라이 시 HP/SAN 손실·상태이상 유지, 상태 초기화 없음 | med |
| P17 | `TRPGGameManager.java` — `buildNpcSystemPrompt` schedule will=반응 주입 (line ~3728) + `GdamGenerator.java` NPC schedule 설계 | will=반응 condition은 단순 위치 이동·방문 금지, 구체적 행위로만 발동 | low |
| P18 | `TRPGGameManager.java` — `buildGmPrompt` comms_monitored 주입 (line ~4480~4482) | '핵심 정보' = collapse/weakness/exploit_path/loophole 직결 내용만, 일반 대화는 오염도+1만 | med |
| P19 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` constraints.gated_zones 스키마 + `buildGmPrompt` gated_zones 주입 | gated_zones 항목에 bypass_dc 필드, 미정의 시 DC 15 | med |
| P20 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 클리어/재시도 판정 (P16 문구에 합산) | 괴담 고유 축적값(채무·감염·저주·NPC 관계)도 리트라이 시 유지, 다음 스테이지 진출 시만 초기화 | med |
| P21 | `TRPGGameManager.java` — `buildNpcSystemPrompt` after_duration 주입 | after_duration은 해당 NPC AI 자율 수행, GM이 임의로 대신 정하지 않음 | low |
| P22 | `GdamGenerator.java` — `generateFamiliarConcept` (line ~674) + clues 설계 (친숙 모드) | 친숙 모드 mislead = 실제 전설의 지역별 이형·구전 변형 버전으로 설계 | med |
| P23 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` collapse_condition/world_rules.hidden 설계부 | 의례형 파훼: world_rules.hidden에 정확 조건 + 오류 시 결과 구조적 필수 명시 | med |
| P24 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 스케일 너프 지침 (line ~73~78) + world_rules.collapse_condition 설계부 | 단일 주체여도 논리적 소멸 경로가 있으면 collapse_condition 채울 것, 순수 회피형이면 스테이지 1~2 배치 | high |
| P25 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 배역 설계 원칙 (line ~111~120) | 단일 주체(npc_dependency=low, _single_entity): 배역 2인 최소 운용 허용, 만남 창 늦게 가능 | med |
| P26 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 괴담 AI 서술 원칙 | entity.independent_ai=true·can_impersonate=false: corruption 단계 순서 엄수, 2단계 건너뜀 금지 | med |
| P27 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` constraints 설계 + `buildGmPrompt` 제약 주입 | 과거 시대 + phone_usable=false: 대체 통신 수단·도달 소요·발각 위험 constraints.notes에 필수 명시 | med |
| P28 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` 클리어 판정 / 순수 회피형 등급 | 순수 회피형 탈출 인원 기반 등급 기준선(전원=C 기본, 과반=D, 1~2인=D~E) | med |
| P29 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` constraints 설계 + `buildGmPrompt` comms_monitored 주입 | 미래 통신 복수 채널 시 constraints.notes에 감청/비감청 채널 목록 명시 | med |
| P30 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` entity.rules 설계부 | 역설형: '이해 시도' 행동 유형 목록과 단계 상승 조건 entity.rules에 명시 | low |
| P31 | `TRPGGameManager.java` — `buildGmPrompt` comms_monitored 주입 (P14 보완) | 감청 후 적의 선제 차단: 감청 턴 직후 다음 1턴 이내 발동(즉시 텔레포트 과반응 금지) | med |
| P32 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 단서·해결 경로 설계 / meeting_design | 필수 핵심 단서·열쇠는 최소 2개 독립 경로로 입수 가능, 단일 출처 금지 | high |
| P33 | `TRPGGameManager.java` — `buildNpcSystemPrompt` schedule will=반응 + `GdamGenerator.java` schedule 스키마 | will=반응에 보조 시간 트리거 병기 허용(N턴 경과 시 NPC가 먼저 최소 전달, 진행 잠금 방지) | med |
| P34 | `GdamGenerator.java` — `generateFamiliarConcept` clues 생성 (친숙 모드 P22 보완) | 친숙 mislead variant_basis에 실제 채록자·연도·지역 의무 포함, 창작 인용 금지 | med |
| P35 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` entity.rules/solution 설계 (의례 격노형 한정) | 의례 오류→격노 즉발 시 임시 대응 행동 최소 1개 함께 명시, 완전 무력 구간 금지 | med |
| P36 | `TRPGGameManager.java` — `runScenarioEvaluation` 평가 프롬프트 (line ~3081~3083) | 소통불가 면제: comm 로그에 시도 없음 + system에 격리 기록 있을 때만 인정, 통신 가능인데 미공유=감점 | high |
| P37 | `TRPGGameManager.java` — `runScenarioEvaluation` 평가 프롬프트 + `buildFullEvalLog` | 고립 구역 진입 system 로그가 있으면 그 구간 미공유=소통불가로 간주(comm 시도 없어도 면제) | med |
| P38 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` + `buildGmPrompt` 행동 판정 | 자발적 희생(앵커·기폭·미끼)은 영웅 서사 선택으로 존중, GM이 임의 차단·강요 금지 | high |
| P39 | `TRPGGameManager.java` — `GM_SYSTEM_BASE` comms/zone 서술 원칙 | 통신두절·고립 구역 진입/이탈 시 '[격리: <구역>·<시점>]' 태그 명시(평가 소통불가 면제 근거) | med |
| P40 | `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` entity.rules/solution 설계 (희생형 한정) | 희생형 collapse: 충격파 영향 범위·안전 대피 완료 기준 필수 명시 | med |
| P41 | `CharacterGenerator.java` (hidden_info 생성부) + `TraitManager.generateRoleTraits` (line ~139) | hidden_info 해결 수단 포함 시 '존재·방법'만, '왜 필요한지(괴담 약점 연결)'는 캐릭터 미인지 | med |
| P42 | `TRPGGameManager.java` — `buildGmPrompt` 연락/통화 판정 (통화 #19 보강) | 번호 미보유 연락 시도 허용, 연결 불가 사유 있으면 '실패+우회 단서' 처리 허용, 시도 차단/성공 강제 금지 | med |
| P43 | `TRPGGameManager.java` — `buildGmPrompt` comms_monitored 주입 (line ~4480~4482) | 도청 대상 = 기기 통신만, 같은 zone 대면 대화는 도청 비대상 자동 명시 | med |

---

## 2. 상호 모순·중복·병합

### 2-1. 모순 분석

**P5 × P17 × P33 — 반응 condition 엄밀화와 진행 보장의 긴장**
- P5는 condition 발생 직후 1턴 발동을 규정하고, P17은 단순 접근·방문 금지로 조건을 엄격화했다. P33은 그 부작용(영구 미발동 위험)을 보조 시간 트리거로 완화한다.
- 이 셋은 상호 보완적이지만 동일 블록에 공존할 때 AI가 혼동할 수 있다. 해소안: "condition 정확 충족이 우선(더 나은 결과), N턴 경과 보조 트리거는 최소 보장"으로 명확히 우선순위를 표기한 통합 문단(아래 §2-2 그룹 A).

**P14 × P43 — comms_monitored 도청 범위 중복**
- P14는 "통신 1회마다 오염도+1"을 규정하고, P43은 "대면 대화는 도청 비대상"을 추가한다. 충돌 없음. 단, 두 패치가 별개 문장으로 같은 주입 블록에 들어가면 총 4줄이 된다. 병합하면 간결해진다(§2-2 그룹 B).

**P24 × P25 — 단일 주체 collapse vs 배역 최소화**
- P24는 "소멸 경로가 있으면 collapse_condition 채울 것"이고 P25는 "배역 2인 최소 운용 허용"이다. 서로 다른 대상(생성 vs 배역 수)이라 충돌 없음. 순수 회피형 예외(collapse 비움, 스테이지 1~2)가 P24에 명시돼 있어 P28(순수회피형 등급선)과도 일관성 유지된다.

**P36 × P37 × P39 — 평가 소통불가 면제 근거 3중 레이어**
- P36: 로그 2조건(comm 시도 없음 + system 격리 기록) 요구
- P37: 고립 구역 진입 system 로그 단독으로도 면제 증거
- P39: GM에게 '[격리: 구역·시점]' 태그 출력 요구
- 잠재적 모순: P37이 "comm 시도가 없어도 격리 기록이 면제 증거"라 했는데, P36은 "comm 시도 없음 + 격리 기록 둘 다"를 AND 조건으로 제시한다. P36이 우선이며 P37은 격리 기록만으로도 충분하다는 뜻이다. 해소안: "격리 기록이 있으면 comm 시도 여부 불문하고 소통불가로 간주(P37 우선)"로 P36의 AND 조건을 완화해서 통합(§2-2 그룹 D).

### 2-2. 병합 통합 패치 텍스트 (그룹별)

---

**그룹 A — NPC schedule will=반응 (P5·P17·P33 통합, 대상: buildNpcSystemPrompt schedule 주입)**

```
반응(will=반응) 처리 규칙:
1. 발동 조건: condition에 명시된 구체적 행위(예: "특정 물건을 건드림"·"이름을 직접 물음")가 실제 일어난 직후 다음 1턴 안에 발동한다.
   - 단순 접근·방문·같은 zone 진입만으로는 발동하지 않는다.
   - condition 문구는 '구체적 행위'로 작성하며, 모호한 상태("방문하면") 금지.
2. 보조 트리거(진행 보장): condition이 N턴 경과 시까지도 충족되지 않으면 NPC가 먼저 다가와 핵심 정보의 일부라도 전달한다.
   - condition 충족 시: 더 좋은 결과(전체 정보·최적 반응).
   - 시간 트리거 발동 시: 최소 보장(단편 정보·불완전 협력).
3. duration_turns 종료 후 after_duration은 해당 NPC AI가 자율 결정·실행하고, GM은 그 결과를 다음 장면 서술에 자연스럽게 녹인다(GM이 임의로 NPC 행동을 대신 정하지 않는다).
```

---

**그룹 B — comms_monitored 도청 처리 (P14·P18·P29·P31·P43 통합, 대상: buildGmPrompt comms_monitored 주입)**

```
- 통신 도청 ★: 괴담이 기기 통신(통화·무전·메시지)을 엿본다. 도청 대상은 '기기를 통한 통신'뿐이다.
  같은 zone 내 직접 대면 대화는 도청 비대상(안전 채널). 플레이어가 대면으로 핵심 정보를 돌리는 전술을 인정하라.
- 감시 통신 1회 사용 시 오염도(timeline_change)+1 또는 적 위협단계 1상승 중 상황에 맞는 쪽을 적용한다.
- '핵심 정보' 정의: collapse_condition·weakness·exploit_path·loophole 직결 내용. 일반 위치·안부 대화는 오염도+1만 적용, 모방 트리거 미발동.
- 핵심 정보를 감시 채널로 넘기면 괴담이 그 대처법을 인지하고 무력화를 시도한다. 단 선제 차단·모방 대응은 감청 턴 직후 다음 1턴 이내에 발동(즉시 텔레포트식 과반응 금지). 플레이어에게 1턴의 대응 여지를 남긴다.
- 미래 통신 복수 채널: constraints.notes에 "감청 대상 채널 = [목록], 안전 채널 = [목록]" 명시 시, 각 채널에 맞춰 적용한다. 안전 채널이 명시된 경우 그 채널을 찾아 핵심 정보를 안전 전달하는 공략을 허용한다.
```

---

**그룹 C — NPC schedule duration_turns (P9·P11·P21 통합, 대상: GdamGenerator GDAM_SYSTEM_PROMPT npc_schedules 스키마 + buildNpcSystemPrompt 주입)**

생성 단계(GDAM_SYSTEM_PROMPT npc_schedules 스키마 설명에 추가):
```
schedule 항목 선택 필드:
- duration_turns: 이 행동/상태가 지속되는 턴 수 (정수, 생략 가능).
- after_duration: duration 종료 후 상태("복귀"/"대기"/"도주" 중 하나, 생략 가능).
```

buildNpcSystemPrompt 주입 추가문:
```
- duration_turns가 있으면 "이 행동/상태는 N턴 지속" 주입.
  N턴 종료 후 after_duration이 있으면 "N턴 후 → [after_duration]"으로 주입.
  after_duration은 해당 NPC AI가 자신의 동기에 따라 자율 결정·실행한다(GM이 임의로 대신 정하지 않는다).
```

---

**그룹 D — 평가 소통불가 면제 판정 (P36·P37·P39 통합, 대상: runScenarioEvaluation 평가 프롬프트)**

기존 line ~3081~3083 부근 "소통 불가 상황" 문구를 아래로 교체·확장:
```
※ '정보 미공유' 감점 판별 기준:
  - 소통불가 면제: 플레이어의 현재 구역에 '[격리: ...]' system 로그 기록이 있으면 그 구간의 미공유는 소통불가로 자동 간주한다(comm 로그에 시도가 없어도 격리 기록만으로 면제 증거로 충분).
  - 통신 가능 구간임에도 공유하지 않은 경우(comm/log에 격리 기록 없고, 공유 수단·기회가 실존): '고의적 은폐'로 감점.
  - 격리 기록 없이 플레이어 본인의 주장만으로 면제 처리 금지(로그 근거 필수).
```

---

**그룹 E — mislead 단서 설계 (P8·P10·P22·P34 통합)**

적용 분기:
- **창작 모드** (대상: GDAM_SYSTEM_PROMPT clues 설계 — 범용):
```
clues에 type="mislead" 단서 1~2개를 필수 포함한다.
mislead 단서는 GM이 '그럴듯한 오답 방향'으로 자연스럽게 흘린다.
발동 타이밍: 진짜 단서가 2개 이상 확보된 뒤 자연스럽게 오답임이 드러나게 한다(처음부터 거짓 티 내지 마라).
plausibility: 플레이어가 mislead를 신뢰해 실제 행동(잘못된 차단·봉쇄 등)을 한 번은 실행해보도록 유도한 뒤 오답을 드러내라.
```
- **친숙 모드** (대상: generateFamiliarConcept clues 설계 + P22·P34):
```
mislead 단서의 variant_basis는 해당 전설의 실제 채록 이형(지역별·시대별 구전 변형 버전)을 근거로 삼는다.
variant_basis에는 실제 채록자·연도·지역(또는 명확한 구전 계통)을 포함한다.
확실한 실제 출처를 못 대면 mislead를 "같은 전설의 다른 지역 변형" 수준으로만 사용하고 가짜 학술 인용 금지.
목적: 원전을 아는 플레이어도 "어느 변형이 맞는지" 헷갈리게 만들어 지식이 함정이 되도록 설계.
```

---

**그룹 F — 단일 주체 괴담 운용 (P24·P25·P26 통합)**

GDAM_SYSTEM_PROMPT 스케일 너프 지침 + 배역 설계 원칙에 조건부 문단 추가:
```
★ 단일 주체 괴담(_single_entity·npc_dependency=low) 전용 규칙:
1. collapse_condition: entity.weakness/exploit_path에서 논리적으로 도출되는 소멸·해제 조건이 있으면 반드시 채워라(탈출 전용으로 비우지 마라).
   진짜 해결 불가한 순수 회피형(collapse 없음)이라면 그 사실을 명시하고 스테이지 1~2 배치 권고(스테이지 3+는 해결판정 필수 진출이라 구조적 충돌).
2. 배역 수: 최소 2인 운용 허용. 만남 창(meeting_window)을 늦게 설정 가능. 대신 단서 비대칭을 충분히 확보해 협력 동기를 유지하라.
   다인 앙상블형은 기존 3~4 배역 원칙 유지.
```

GM_SYSTEM_BASE에 단락 추가:
```
★ 단일 독립 AI 운용 원칙 (entity.independent_ai=true · can_impersonate=false):
- ai_context.corruption_behavior의 단계 순서를 반드시 준수한다. 한 응답에서 corruption 단계를 2단계 이상 건너뛰지 마라.
- 위협은 물리적 현상·환경 변화로만 간접 표현. 직접 묘사로 신비를 소진하지 마라(단일 주체에서 특히 엄격히).
```

---

**그룹 G — 시나리오 평가/등급 (P1·P12·P36·P38 통합)**

GM_SYSTEM_BASE 클리어 판정 섹션:
```
등급 판정 원칙:
★ 등급은 최종 성공 시점의 조건(해결/생존·생존자 수·타임라인 단계)만으로 매긴다. 재도전을 거쳤다는 이유만으로 등급을 내리지 마라.
★ collapse_condition 완전 충족 + 위협권 인원 전원 탈출/생존 = 해결판정(resolved=true).
   collapse 부분 충족이나 잔류·미구출 발생 = 생존판정 또는 등급 강등. 모호하면 생존판정 쪽으로.
★ 자발적 희생(앵커·기폭·미끼 등)은 정당한 영웅 서사 선택으로 존중한다. GM이 임의로 차단(과보호)하거나 강요하지 마라.
   희생 플레이어가 선택하면 그 결과(전원 생존·캐리 등급)를 정당히 반영하고, 선택하지 않으면 대안 경로를 막지 마라.
```

---

**그룹 H — 클리어/리트라이 상태 유지 (P16·P20 통합)**

GM_SYSTEM_BASE 클리어/재시도 판정:
```
★ 스테이지 내 재시도(리트라이) 진입 시:
- HP/SAN 손실·상태이상은 유지된다. 상태 초기화 없음.
- 괴담 고유 축적값(채무·감염·저주 스택·오염도 누적·NPC 관계 등 시나리오 종속 수치)도 유지된다.
- 단, '다음 스테이지 진출' 시에는 전원 부활과 함께 이 수치들이 초기화된다.
```

---

**그룹 I — 해결 경로 강건성 (P23·P32·P35 통합)**

GDAM_SYSTEM_PROMPT 단서·해결 경로 설계부:
```
★ 해결 경로 단일 장애점 금지:
collapse_condition 달성에 필수인 핵심 단서·열쇠는 최소 2개의 독립 경로로 입수 가능하게 설계하라
(문서/NPC/환경 단서 중 둘 이상). 하나가 막혀도(NPC 사망·구역 봉쇄) 대체 경로로 해결 가능해야 한다.
단일 출처면 그 출처의 보호·복원 수단을 함께 둔다.

★ 의례형 파훼(solution이 의례·정확입력형이면):
world_rules.hidden 또는 entity.solution에 "정확 조건 = X(구체), 오류 시 결과 = Y(부분피해·오염도·리트라이 등)"를 구조적으로 명시하라.
오류결과가 '격노·즉발 반격'형이면 entity.escape 또는 hidden_rules에 그 순간의 임시 대응 행동(도피 경로·연기 차단·미끼 등) 최소 1개를 함께 명시. 완전 무력 구간 금지.
GM은 이 경계로만 성공/실패를 가른다.

★ 희생형 collapse(희생으로 collapse 달성 시):
entity.rules 또는 solution에 "충격파/붕괴 영향 범위(구역)와 안전 대피 완료 기준(예: 폭발 1턴 전 인접구역 밖)"을 필수 명시.
```

---

### 2-3. 조건부 적용 분리 표

| 패치 | 조건 | 분리 이유 |
|------|------|-----------|
| P4 | 소음 트리거 괴담 한정 | constraints.notes에만 들어감, 범용 주입 시 비소음 괴담 왜곡 |
| P22·P34 | 친숙(familiar) 모드 한정 | generateFamiliarConcept 경로에서만 적용, 창작 모드와 mislead 설계 원리 다름 |
| P23·P35 | 의례형 파훼(ritual-solution) 시나리오 한정 | entity.solution이 의례·정확입력형일 때만, 탈출·회피형에 주입 불필요 |
| P25·P26 | 단일 주체(_single_entity) 괴담 한정 | 다인 앙상블형에 배역 최소화 적용 시 구조 손상 |
| P27 | 과거 시대(phone_usable=false + era=과거) 한정 | 현대·미래는 P14/P29로 처리, 혼용 금지 |
| P28 | 순수 회피형(collapse 없음) 한정 | 해결형 등급선과 구분 필요 |
| P29 | 미래 통신 복수 채널 시나리오 한정 | constraints.notes에 채널 목록이 있을 때만 분기 |
| P30 | 역설형(이해/접근 시 악화) 괴담 한정 | 역설 메커니즘 없는 괴담에 트리거 목록 불필요 |
| P40 | 희생형 collapse 시나리오 한정 | 비희생형에 충격파 범위 명시 불필요 |

---

## 3. 커버리지·누락

### 3-1. 검증 완료 축 (iter01~14)

| 축 | 검증 내용 | 검증 회차 |
|----|----------|----------|
| 스테이지 | 1~5 (S1 2회, S2 3회, S3 5회, S4 2회, S5 1회) | iter02·09·04·07·13·01·06·08·10·11·12·14·05·03 |
| 스케일 | 로컬·시티·내셔널·글로벌 모두 | iter02·09·01·06·07·08·10·11·12·13·14·05·03 |
| 시대 | 과거20C(1980s)·먼과거(1780s조선·1963부산)·현대·근미래(2054)·먼미래(2347) | iter02·08·04·06·05·09 |
| 괴담 구조 | 다인앙상블·단일해결형·단일회피형(순수회피) | iter01~07·08·09 |
| 모드 | AI창작·친숙(familiar) 2샘플(El Silbón·루살카) | iter01~06·07·10 |
| 클리어 판정 | 해결판정·생존판정·부분collapse·재도전 | iter01·02·04·06·08·09 |
| 통신 | comms_monitored 도청·과거 대체통신·미래 복수채널·@통화 통화 심층 | iter05·06·08·09·14 |
| 평가/보상 | 정보비대칭·캐리·희생·소통불가 면제·고의 은폐 감점·특성보상 | iter11·12 |
| 캐릭터/특성 | 시트 다양성·시작파워 차등·액티브특성 일관성·반스포일러 | iter13 |
| 강제 결말 | E_END(#13) 타임라인 4단계 강제 배드엔딩 서술 | iter13 |
| 가짜 단서 | mislead 설계·발동 타이밍·친숙 이형 | iter04·07·10 |
| 해결 경로 강건성 | 단일 장애점 방지·NPC 보조 트리거 | iter10·11 |

### 3-2. 미검증 또는 검증 부족 영역

1. **NPC 다수 복합 오케스트레이션** — critical NPC 2명 이상이 동시에 독립 AI로 움직이며 서로 영향을 주는 시나리오 미검증. 현재 3턴마다 NPC 워치독이 작동하지만, 2명의 NPC가 충돌하거나 협력하는 경우 GM 조율 기준 미확인.

2. **정보 자동기록 가변성** — `buildFullEvalLog`가 실제로 어떤 정보를 얼마나 상세히 캡처하는지, 고립 구역 '[격리]' system 로그가 CODE-6 없이 GM 서술에만 의존할 때 평가 AI에게 얼마나 전달되는지 실제 검증 미완(P39 효과의 코드 미지원 상태).

3. **무행동 워치독 극단 케이스** — NPC beat가 4턴 이상 없을 때 강제 등장(line 1857)이 실제로 평가 로그에 어떻게 반영되는지, 그리고 이것이 NPC 반응 타이밍(P5·P17) 지침과 충돌하지 않는지 미검증.

4. **스테이지 6~10(내셔널~코즈믹) 심층** — iter03에서 스테이지5 내셔널을 1회 검증했으나, 스테이지7+ 글로벌·코즈믹·복합 괴담 연계 경로 미검증. zones 규모 최대치(9~12개) + 다수 main_events 연쇄 분기 행동 미확인.

5. **캐릭터 레거시/이월 시스템** — 한 플레이어가 사망 후 다음 스테이지에서 새 캐릭터로 합류하는 흐름, 특성 이월·유산 포인트 연계 미검증.

6. **INSTANT_CLEAR 특성 + 희생형 collapse 동시 발동** — `activateInstantClear` (line 2355)와 희생 기믹이 같은 회차에 발동할 때 우선순위·평가 로그 처리 미검증.

7. **can_impersonate=true 단일 주체** — P26은 can_impersonate=false에만 적용. 변장/위장 가능 단일 주체(교환원류)의 AI 운용 지침은 별도 미작성.

---

## 4. 최종 권고 프롬프트 패치셋 (우선순위)

### HIGH 우선순위 (즉시 반영)

**[H-1] P1 + P12 + P38 (그룹 G 통합) — GM_SYSTEM_BASE 클리어 판정 섹션**

위치: `TRPGGameManager.java` — `GM_SYSTEM_BASE` 상수 내 클리어 판정/grade 기준 섹션 (line ~304~347 근방)

추가 위치: grade 기준 설명 직전 또는 직후에 독립 단락으로 삽입:
```
★ 등급 판정 3원칙:
(1) 재도전 강등 금지: 등급은 최종 성공 시점의 조건만으로 매긴다. 재도전 횟수 자체는 등급을 낮추지 않는다.
(2) 해결/생존 경계: collapse_condition 완전 충족 + 위협권 인원 전원 탈출/생존=해결판정. 부분 충족이나 잔류·미구출=생존판정 또는 등급 강등. 모호하면 생존판정.
(3) 희생 수용: 자발적 희생(앵커·기폭·미끼)은 영웅 서사 선택. GM이 임의로 차단하거나 강요하지 마라. 희생 결과(전원 생존·캐리 등급)를 정당히 반영한다.
```

**[H-2] P36 + P37 (그룹 D 통합) — runScenarioEvaluation 평가 프롬프트**

위치: `TRPGGameManager.java` — `runScenarioEvaluation` 메서드 내 prompt String (line ~3081~3083)

기존 "소통 불가 상황..." 문구를 아래로 교체:
```
'정보 미공유' 감점 판별: 플레이어 구역에 '[격리: ...]' system 로그가 있으면 그 구간의 미공유는 소통불가로 자동 간주(comm 시도 없어도 면제 충분). 격리 기록 없이 통신 가능 구간에서 미공유면 고의적 은폐로 감점. 본인 주장만으로는 면제 불가(로그 근거 필수).
```

**[H-3] P24 (그룹 F 통합) — GDAM_SYSTEM_PROMPT 스케일 너프 지침**

위치: `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 상수 내 스케일 너프 지침 블록 (line ~72~78) 직후 삽입:
```
★ 단일 주체 괴담 collapse_condition 의무화:
entity.weakness/exploit_path에서 논리적으로 도출되는 소멸·해제 조건이 있으면 world_rules.collapse_condition을 반드시 채워라(탈출 전용으로 비우지 마라).
진짜 해결 불가한 순수 회피형이면 그 사실을 명시하고 스테이지 1~2 배치 권고(스테이지 3+는 해결판정 필수라 구조적 충돌).
```

**[H-4] P32 (그룹 I 부분) — GDAM_SYSTEM_PROMPT 단서·해결 경로 설계부**

위치: `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 단서 설계 섹션 또는 meeting_design 섹션 직후:
```
★ 해결 경로 단일 장애점 금지:
collapse_condition 달성에 필수인 핵심 단서·열쇠는 최소 2개의 독립 경로(문서/NPC/환경 단서 중 둘 이상)로 입수 가능하게 설계하라. 하나가 막혀도(NPC 사망·구역 봉쇄) 대체 경로로 해결 가능해야 한다. 단일 출처면 그 출처의 보호·복원 수단을 함께 둔다.
```

**[H-5] P6 — GM_SYSTEM_BASE 판정 시스템 섹션**

위치: `TRPGGameManager.java` — `GM_SYSTEM_BASE` 판정 섹션 (line ~241 근방, 스탯 판정 설명 직후):
```
협력 판정: 주도자 d20 + 보조 1인당 +2(최대 +6).
```

---

### MED 우선순위 (다음 작업 시)

**[M-1] 그룹 B (P14·P18·P29·P31·P43) — buildGmPrompt comms_monitored 주입**

위치: `TRPGGameManager.java` — `buildGmPrompt` line ~4480~4482의 comms_monitored 조건 블록을 그룹 B 통합 문구로 교체.

**[M-2] 그룹 A (P5·P17·P33) — buildNpcSystemPrompt schedule 주입**

위치: `TRPGGameManager.java` — `buildNpcSystemPrompt` line ~3728~3741의 schedule 루프 직후, 현재 "의지가 '강함'이면..." 문장 아래에 그룹 A 통합 문구 추가.

**[M-3] 그룹 C (P9·P11·P21) — GdamGenerator GDAM npc_schedules 스키마 + buildNpcSystemPrompt**

위치(생성): `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` npc_schedules 섹션 (line ~321~348)에 duration_turns·after_duration 필드 설명 추가.
위치(주입): `buildNpcSystemPrompt` schedule 루프 내 sb.append 블록.

**[M-4] P39 — GM_SYSTEM_BASE comms/zone 서술 원칙**

위치: `TRPGGameManager.java` — `GM_SYSTEM_BASE` 통신/구역 관련 서술 원칙 섹션 (통신 도청 관련 항목 근방):
```
★ 격리 서술 의무: 캐릭터가 통신두절·고립 구역에 들어가거나 나올 때마다 서술에 '[격리: <구역>·<시점>]'을 명시하라(평가의 소통불가 면제 판정 근거). 구간 종료 시 '[격리 해제]'도 남긴다.
```

**[M-5] P41 — CharacterGenerator hidden_info 생성 + TraitManager.generateRoleTraits**

위치: `CharacterGenerator.java` — hidden_info 생성 로직 / `TraitManager.java` — `generateRoleTraits` system 프롬프트 (line ~155 근방):
```
hidden_info에 해결 수단(아이템·경로·조작법)이 포함되면, 그 '존재·방법'만 알게 하고 '왜 필요한지(괴담 약점·해법과의 연결)'는 캐릭터가 인지하지 못한 상태로 설계하라. 정답을 처음부터 쥐여주지 마라.
```

**[M-6] P3 — buildGmPrompt 타임라인 시계 주입**

위치: `TRPGGameManager.java` — `buildGmPrompt` line ~4522~4524 (TIME_SKIP 관련 문장) 보강:
```
탐색·대기·장면 전환 등 평온 구간에서는 적극적으로 <TIME_SKIP>으로 수십 분~수 시간을 건너뛰어라. 추격·대치 등 급박 구간에서는 1턴을 수 분 이내로 좁혀라. 매 턴 동일 간격으로 흐르게 두지 마라.
```

---

### 조건부 적용 (괴담 유형 감지 시만)

**[C-1] 그룹 I 의례·희생 부분 (P23·P35·P40)** — GDAM_SYSTEM_PROMPT에 solution/entity.rules 조건부 지침으로 삽입(의례형·희생형 각 분기 명시).

**[C-2] 그룹 F 단일 주체 배역·AI 부분 (P25·P26)** — GDAM_SYSTEM_PROMPT 배역 설계 원칙 + GM_SYSTEM_BASE에 `_single_entity` 조건 분기로 삽입.

**[C-3] P27 (과거 대체통신)** — GDAM_SYSTEM_PROMPT constraints 설계부에 "era=과거 + phone_usable=false → constraints.notes 대체통신 기준 필수" 조항 추가.

**[C-4] P28 (순수 회피형 등급선)** — GM_SYSTEM_BASE 클리어 판정 섹션에 "collapse 없는 순수 회피형" 분기 등급 기준선 추가.

**[C-5] P4 (소음 트리거 통화 소음 처리)** — GDAM_SYSTEM_PROMPT constraints.notes 설계 원칙에 "소음 트리거 괴담이면 통화 음성=의도적 소음 동등 취급 명시" 조건 주석 추가.

**[C-6] P13·P15·P19 (gated_zones·대규모 zones)** — GDAM_SYSTEM_PROMPT constraints 스키마 + buildGmPrompt zones 주입: 이미 설계 방향 확정, gated_zones[] 배열과 bypass_dc 필드 추가, 6구역+ 시 접근권 계층 주입.

**[C-7] P22·P34 (친숙 mislead 이형)** — generateFamiliarConcept 경로에서 clues 생성 시 variant_basis 의무화.

**[C-8] P29·P30 (미래 채널·역설형)** — constraints.notes 또는 entity.rules에 조건부 주입.

**[C-9] P42 (번호 미보유 연락 실패·우회)** — buildGmPrompt 연락/통화 판정 섹션에 한 줄 추가(통화 시스템 #19 보강).

---

## 5. 코드 TODO (CODE-1~10) 심각도순 정리

### ★★ HIGH (즉시 작업 권장)

| CODE | 파일·위치 | 증상 | 수정 방향 | severity |
|------|----------|------|----------|----------|
| CODE-3 | `TRPGGameManager.java` — `grantClearTraitRewards` line 3006 `.filter(playerData -> !playerData.isDead)` | 사망한 캐리어(S/A 등급)가 특성 보상 대상에서 완전 제외됨. 희생으로 동료를 살리고 죽어도 다음 스테이지 보상 없음. 사용자 의도("사망해도 캐리하면 S" + 특성 보상)와 정면 충돌. | isDead=true이더라도 playerGrades에서 S/A인 플레이어에게 예외 보상 경로 신설. 정책 후보: (a) 다음 캐릭터에게 1회 이관(유산 포인트), (b) 현 캐릭터가 부활 시 1회 추가 선택권. 정책 결정 필요(사용자). | ★HIGH |
| CODE-4 | `TRPGGameManager.java` — `computeWeaknessBonus` line 2268~2271 `(10 - hpSanBase) / 2` | hpSanBase가 10 이상인 캐릭터(HP3+SAN7=10도 포함)는 weaknessBonus=0으로 '시작파워 낮을수록 강한 보상' 취지가 사문화. 대부분 캐릭터(hpSanBase≥10)가 혜택 못 받음. iter13에서 high로 격상 확인. | 기준선을 `(14 - hpSanBase) / 2`로 확장. 결과: hpSanBase=14→0, 12→1, 10→2, 8→3(상한 유지). 실제 코드 1줄 수정. | ★HIGH |
| CODE-6 | `TRPGGameManager.java` / `GameStateManager.java` — zone 진입/이탈 처리 로직 | P36/P37/P39의 평가 소통불가 면제 판정이 GM 서술 '[격리: ...]' 수동 태그 의존. GM 서술 누락 시 억울한 감점 발생. 자동화 없이는 평가 공정성 보장 불가. | zone 진입/이탈 시 ZONE_ISOLATION(격리)·해제 system 로그를 `GameStateManager`가 자동 생성. `buildFullEvalLog`에 ZONE_ISOLATION 로그가 포함되도록 연결. P36 평가 프롬프트가 이 로그를 면제 증거로 참조. | ★HIGH |
| CODE-8 | `TRPGGameManager.java` — E_END(is_end=true) 강제 실패 종료 처리 경로 | 타임라인 4단계 is_end 이벤트 발화(E_END) 후 GM이 서술로 배드엔딩을 묘사하지만, `onClearEnding`이 미호출 → `runScenarioEvaluation`·실패 등급 기록이 코드상 실행되지 않음. 실패 회차는 평가도, 후일담도, 페널티도 없음. | E_END 이벤트(is_end=true) 처리 경로에서 `onBadEnding` 대신 또는 연계해 `onFailureEnding()` 경로 신설 → `runScenarioEvaluation(실패 등급 D~E)` + 특성 보상(제한적) + 후일담 공개를 정식 연결. iter13 #13 핵심 미구현부. | ★HIGH |
| CODE-9 | `TRPGGameManager.java` — `handleNpcDirectComm` line 3981, 3988~3990 | `handleNpcDirectComm`이 `senderPd.zone == npcZone` 조건을 강제 → phone_usable=true라도 다른 구역·외부 기관 NPC에게 전화로 연락 불가. 통화 기믹 시나리오(iter14 '사신의 교환원')의 핵심 구조적 한계. | 분기 조건 추가: 플레이어가 기기(전화) 보유 + NPC에 phone_number가 있으면 zone 무관 통화 허용(원격 통화 경로 신설). 대면 행위(같은 zone)와 원격 통화를 코드 분기로 분리. | ★HIGH |

---

### MED (다음 작업 사이클)

| CODE | 파일·위치 | 증상 | 수정 방향 | severity |
|------|----------|------|----------|----------|
| CODE-5 | `TRPGGameManager.java` / `GameStateManager.java` — comm 로그 기록 로직 | 실패한 통신 시도(연결 불가·도달 불가)가 comm 로그에 기록되지 않아, 평가 AI가 '시도조차 없음'으로 오인 가능. P37의 프롬프트 보완으로 일부 완화되나 근본 해결은 코드. | 실패한 통신 시도도 comm 로그에 "시도(실패): 대상·사유"로 기록. `buildFullEvalLog`에 포함. | med |
| CODE-7 | `CharacterGenerator.java` — `applyAiAdjustment` (line ~538) vs `ensureSurvivalFloor` (line ~353) 호출 순서 | `applyAiAdjustment`가 `ensureSurvivalFloor` 이후 적용돼 AI 음수 조정이 생존 하한(HP/SAN≥1)을 다시 무너뜨릴 수 있음(SAN<1 재발 가능). | `applyAiAdjustment` 후 `ensureSurvivalFloor` 재호출, 또는 `applyAiAdjustment` 내부에서 하한을 보장하는 방식으로 순서 변경. | med |
| CODE-10 | `TRPGGameManager.java` / `PlayerData` — `everKnownContacts` (UUID 기반, line ~886·4053·4054·4105·4112·4132) | `everKnownContacts`가 PlayerData(UUID) 기반이라 NPC 연락처(문자열 ID) 저장 불가. CONTACT_REVEAL로 알게 된 NPC 번호가 리트라이 시 소실 → 2회차에 NPC 재연락하려면 #19(번호 재탐색) 재시도 강제. | `PlayerData`에 `everKnownNpcContacts(Set<String> npcId)` 신설. CONTACT_REVEAL 처리 시 npc_id를 everKnownNpcContacts에 추가. `onRetry`(line ~886 근방)에서 복구. | med |

---

### LOW (여유 작업)

| CODE | 파일·위치 | 증상 | 수정 방향 | severity |
|------|----------|------|----------|----------|
| CODE-1 | `GdamGenerator.java` — `generateFamiliarConcept` (line ~674) region 선택 로직 | region 선택이 roomNumber 의존이라 초반 인덱스 편향 가능(같은 region 반복). | region 오프셋을 roomNumber 무관 무작위로 분리(Random 호출). | low |
| CODE-2 | `TRPGGameManager.java` — `buildGmPrompt` room 분기 (line ~4427) | P7의 "room≤2 생존판정 진출 가능" 문구가 수동 주입 의존 → 빠질 수 있음. | `buildGmPrompt`에서 room≤2일 때 특례 문장을 자동 주입하는 코드 블록 추가. | low |

---

## 6. 총평

### 6-1. 프롬프트 수렴 여부

**수렴 확정**. iter13·14 2회 연속으로 신규 high severity 프롬프트 패치가 발견되지 않았다(iter13: P41 med 1건만, iter14: P42·P43 med 2건만). 잔여 약점은 코드 도메인(CODE-3/4/6/8/9)으로 이동했다.

수렴 근거:
- iter07 말미 "수렴 접근" 신호 → iter08에서 새 구조축(단일 주체)에서 high 1건 재발견으로 번복
- iter10·11·12에서 각 1건씩 high 발견(P32·P36·P38) — 평가/희생/해결경로 축이 잔여 취약점으로 확인
- iter13·14에서 상기 3축 모두 검증 완료(P36·P38·P32 pass), 신규 high 없음 → 실질 수렴

### 6-2. 잔여 리스크

1. **CODE-3 (사망 캐리어 보상 미지급)**: 가장 사용자 경험에 직접 영향을 미치는 버그. 정책 결정이 선행돼야 코드 구현 가능.
2. **CODE-8 (E_END 평가 미호출)**: 강제 실패 회차의 평가·보상이 전혀 없어 시스템 불완전. 희생 서사가 빛날 수 없는 구조.
3. **CODE-6 (ZONE_ISOLATION 자동 로그 없음)**: P36/P37/P39의 평가 공정성 보장이 GM 수동 태그에 의존 — 프롬프트 수렴과 무관하게 코드 지원이 없으면 실질적 보장 불가.
4. **미래 채널·역설형 등 niche 시나리오**: 검증은 1회씩이라 추가 edge case가 있을 수 있다.

### 6-3. 루프 종료 후 스팟체크 추천 (1~2개)

- **스팟체크 A — NPC 2인 동시 독립 AI 운용**: critical NPC 2명이 서로 다른 zone에서 동시에 자율 행동하고, 플레이어와 각각 독립 상호작용하는 시나리오. 워치독(4턴 미등장 강제)과 P5/P17의 반응 타이밍이 실제로 충돌하지 않는지, 그리고 NPC 간 정보 교환 시 GM 컨텍스트 누적이 과부하 없이 작동하는지 확인.

- **스팟체크 B — CODE 수정 후 평가 공정성 재검증**: CODE-3(사망 캐리어 보상)·CODE-6(ZONE_ISOLATION 자동 로그)·CODE-8(E_END 평가 연결)을 수정한 뒤, iter11/12와 유사한 정보비대칭·희생 시나리오를 1회 재실행해 수정 결과가 실제로 평가 로직에 반영되는지 확인.

---

*SYNTHESIS.md 작성 완료 — 2026-06-26*  
*프롬프트 패치 P1~P43 전수 매핑 · 코드 TODO CODE-1~10 심각도순 정리 완료*
