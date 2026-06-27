# FINAL_PROMPT_PATCHES.md
# 괴담 TRPG 프롬프트 자가개선 루프 — 최종 적용본
# 작성 기준: 2026-06-26 | 18회 시뮬 완전 수렴 | P1~P48(+P44b) + CODE-1~12 전수 반영

---

## A. 적용 개요

18회 시뮬레이션(iter01~iter17) 결과, 프롬프트 도메인은 iter13~14 연속 무(無)high 이후 실질 수렴하였으며, iter15~17 스팟체크 3회에서 can_impersonate 축(P44) 1건이 추가 발견되어 최종 49건(P44b 포함)으로 확정되었다. 잔여 미해결 문제는 전량 코드 도메인(CODE-3/4/6/8/9/12)으로 이동하였다.

**권장 적용 순서:**
1. HIGH 프롬프트 그룹 (G1 클리어·G2 평가면제·G3 단일주체collapse·G4 해결경로·G5 협력판정·G6 can_impersonate위장)
2. MED 범용 그룹 (G7 comms도청·G8 NPC반응schedule·G9 리트라이상태유지)
3. 조건부 그룹 (C절 분리표 참조 — 범용 주입 금지)
4. 코드 TODO HIGH (CODE-3·CODE-4·CODE-6·CODE-8·CODE-9·CODE-12) — 정책 결정 선행 항목 있음

---

## B. 통합 그룹별 '바로 붙여넣는' 프롬프트 텍스트

---

### G1. 클리어 등급·희생·해결/생존 경계 통합
**포함 패치:** P1, P12, P16, P20, P38, P40  
**severity:** HIGH  
**대상:** `TRPGGameManager.java` — `GM_SYSTEM_BASE` 상수 내 클리어 판정 섹션  
**삽입 앵커:** `"grade 기준:\nS: 해결판정 + 전원 생존 + 타임라인 2단계 이하"` 문장 직전 (line ~339)에 독립 단락으로 삽입

**삽입 텍스트:**
```
★ 등급 판정 4원칙 (최우선):
(1) 재도전 강등 금지: 등급은 최종 성공 시점의 조건(해결/생존 여부·생존자 수·타임라인 단계)만으로 매긴다. 재도전을 거쳤다는 이유만으로 등급을 내리지 마라. 오염도는 시스템이 보상에 별도 반영한다.
(2) 해결/생존 경계: ★해결판정(resolved:true)은 collapse_condition 완전 충족 여부로만 가른다 — 사망자가 있어도 collapse를 완전히 충족했으면 해결판정이다(전원 생존 불필요). 생존자 수는 '등급(S~F)'에만 영향(전원 생존=상향 / 사망·잔류=등급 일부 하향하되, 해결판정 자체를 생존판정으로 강등하지 마라). collapse 미충족 상태로 탈출·생존만 했으면 생존판정(resolved:false). 모호하면 collapse 충족 여부로 판정.
(3) 죽음의 의도 판별 (자살/희생 처리 — 맥락으로 분류, 결과를 다르게):
   ⓐ 영웅적 희생: collapse 달성·동료 구출에 직접 기여하는 죽음 → 정당한 영웅 서사로 존중·캐리 인정·높은 등급(S 가능). GM이 차단(과보호)하거나 강요하지 마라.
   ⓑ 전략적 양도(배턴패스): 충분히 시도한 뒤 '현재로선 답이 없다'고 판단해, 자신의 죽음으로 다음 플레이어에게 기회를 넘기는 선택. ★고의 트롤이 아니다(상황 악화 리스크를 감수한 정당한 전략) — 감점·트롤 처리 금지. 기여도는 '소극적'으로만 평가(높은 등급은 아니되 페널티 없음).
   ⓒ 무의미·악의 트롤: 시도·맥락 없이 즉시·반복적으로 자해해 진행을 방해하는 경우에만 트롤로 처리.
   ※ 혼자 생존 중이던 플레이어가 죽으면, 다음 플레이어(타임라인 후속 등장)가 나타날 때까지 타임라인을 가속(TIME_SKIP)해 공백을 빠르게 넘긴다. 그 사이 상황은 seed 규칙대로 악화될 수 있다.
(4) 희생형 collapse 범위 기준: 희생으로 collapse 달성 시, entity.rules 또는 solution에 명시된 "충격파/붕괴 영향 범위(구역)와 안전 대피 완료 기준(예: 폭발 1턴 전 인접구역 밖)"으로만 희생자 외 인원의 생존을 판정한다. 자의적 판정 금지.

★ 스테이지 내 재시도(리트라이) 진입 시:
- HP/SAN 손실·상태이상은 유지된다. 상태 초기화 없음.
- 괴담 고유 축적값(채무·감염·저주 스택·오염도 누적·NPC 관계 등 시나리오 종속 수치)도 유지된다.
- 단, '다음 스테이지 진출' 시에는 전원 부활과 함께 이 수치들이 초기화된다.
```

---

### G2. 평가 — 소통불가 면제·INSTANT_CLEAR 일괄 하향 방지
**포함 패치:** P36, P37, P39, P48  
**severity:** HIGH (P36·P37) + MED (P39·P48)  
**대상:** `TRPGGameManager.java` — `runScenarioEvaluation` 메서드 내 prompt String  
**삽입 앵커:** `"※ '정보 미공유' 감점은 신중히 적용하라. 공유할 수단·기회가 실제로 있었는데"` 문장 (line ~3081)을 아래 텍스트로 전체 교체

**교체 텍스트:**
```
※ '정보 미공유' 감점 판별 기준 (로그 근거 필수):
  소통불가 면제: 플레이어의 현재 구역에 '[격리: ...]' system 로그 기록이 있으면 그 구간의 미공유는 소통불가로 자동 간주한다. comm 로그에 시도가 없어도 격리 기록만으로 면제 증거로 충분하다.
  고의적 은폐 감점: 격리 기록이 없고 통신 가능 구간임에도 공유하지 않은 경우(comm/log에 격리 기록 없고, 공유 수단·기회가 실존). 플레이어 본인의 주장만으로 면제 처리 금지.
★ INSTANT_CLEAR류 즉시 종료(clearGrade=F)로 끝난 회차에서는, 발동자 외 플레이어를 clearGrade=F 이유로 일괄 하향하지 말고 각자의 실제 행동 기록으로만 평가하라. 발동자는 기여를 반영하되 penalties에 '조기 철수(미해결 종료 유도)'를 기록한다(상황상 정당한 철수면 감점 완화).
★ 죽음(자살) 평가: 캐릭터의 자발적 죽음은 맥락으로 분류해 평가한다 — ⓐ영웅적 희생(collapse 기여)=캐리 인정·등급 상향 가능(S, 사망 무관). ⓑ전략적 양도(충분히 시도 후 '답 없음' 판단→다음 플레이어에게 양도)=고의 트롤 아님, ★감점·트롤 처리 금지(소극적 기여로 중립 평가). ⓒ무의미·악의 반복 자해만 penalties에 트롤로 기록. 로그(시도 흔적·타임라인 정황)로 ⓐⓑⓒ를 구분하라.
```

---

### G3. 단일 주체 괴담 — collapse_condition 의무화·배역·AI 운용
**포함 패치:** P24, P25, P26  
**severity:** HIGH (P24) + MED (P25·P26)  
**적용 분기:** `_single_entity` 또는 `npc_dependency=low` 단일 주체 괴담 조건부

**대상 1:** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 스케일 너프 지침 블록 (line ~72~78)  
**삽입 앵커:** `"- 괴담 자체의 강함은 유지하되, 접촉/피해 기회를 제한"` 문장 직후에 삽입

**삽입 텍스트 (생성 지침):**
```
★ 단일 주체 괴담(_single_entity·npc_dependency=low) 전용 규칙:
1. collapse_condition 의무화: entity.weakness/exploit_path에서 논리적으로 도출되는 소멸·해제 조건이 있으면 world_rules.collapse_condition을 반드시 채워라(탈출 전용으로 비우지 마라). 진짜 해결 불가한 순수 회피형(쿠네쿠네類, collapse 없음)이면 그 사실을 명시하고 스테이지 1~2 배치 권고(스테이지 3+는 해결판정 필수 진출이라 구조적 충돌 발생).
2. 배역 수 완화: 최소 2인 운용 허용. 만남 창(meeting_window)을 늦게 설정 가능. 대신 단서 비대칭을 충분히 확보해 협력 동기를 유지하라. 다인 앙상블형은 기존 3~4 배역 원칙 유지.
```

**대상 2:** `TRPGGameManager.java` — `GM_SYSTEM_BASE` 상수 내 괴담 AI 서술 원칙 섹션  
**삽입 앵커:** `"### GM 내부 비공개 항목 (절대 공개 금지)"` 문장 직전에 삽입

**삽입 텍스트 (GM 운용 지침):**
```
★ 단일 독립 AI 운용 원칙:

[entity.independent_ai=true · can_impersonate=false 단일 주체]
- ai_context.corruption_behavior의 단계 순서를 반드시 준수한다. 한 응답에서 corruption 단계를 2단계 이상 건너뛰지 마라.
- 위협은 물리적 현상·환경 변화로만 간접 표현한다. 직접 묘사로 신비를 소진하지 마라(단일 주체에서 특히 엄격히 적용).

[entity.independent_ai=true · can_impersonate=true 위장 단일 주체]
- (1) 위장 유지 중에는 corruption 단계 진행을 1단계 늦춘다(위장은 비용이 든다).
- (2) 위장 해제 후 직접 반격(공격·공간 압박)은 해제된 그 턴 직후 1턴 이내(P31 타이밍과 동일).
- (3) 위장 재활성화는 해제 후 최소 2턴 쿨다운.
- (4) 발동 트리거: 플레이어가 핵심정보(collapse_condition·weakness·exploit_path·loophole 직결 내용)를 '위장 대상'에게 감청 가능 채널(통화·무전 등)로 전달한 comm 로그가 실제로 존재할 때만 → 위장 강화로 corruption 1단계 상승 + 그 정보를 즉시 흡수·역이용. 단순 동행·대화만으로는 미발동. 대면 안전채널(같은 zone 직접 대화)은 흡수 제외.
- (5) 위장은 ai_context의 단계 순서를 어기지 않는다(독립 AI 원칙과 호환).
```

---

### G4. 해결 경로 강건성 — 단일 장애점 금지·의례·희생형
**포함 패치:** P23, P32, P35, P40  
**severity:** HIGH (P32) + MED (P23·P35·P40)  
**대상:** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` clues 설계부 또는 meeting_design 섹션 직후  
**삽입 앵커:** `"## 만남 가능성 검증 (출력 전 필수)"` 문장 직전에 삽입

**삽입 텍스트:**
```
★ 해결 경로 강건성 규칙 (생성 시 필수 점검):

1. 단일 장애점 금지: collapse_condition 달성에 필수인 핵심 단서·열쇠는 최소 2개의 독립 경로(문서/NPC/환경 단서 중 둘 이상)로 입수 가능하게 설계하라. 하나가 막혀도(NPC 사망·구역 봉쇄) 대체 경로로 해결 가능해야 한다. 단일 출처면 그 출처의 보호·복원 수단을 함께 둔다.

2. 의례형 파훼(solution이 의례·정확입력형이면): world_rules.hidden 또는 entity.solution에 "정확 조건 = X(구체), 오류 시 결과 = Y(부분피해·오염도·리트라이 등)"를 반드시 구조적으로 명시하라. 오류결과가 '격노·즉발 반격'형이면 entity.escape 또는 hidden_rules에 그 순간의 임시 대응 행동(도피 경로·연기 차단·미끼 등) 최소 1개를 함께 명시. 완전 무력 구간 금지. GM은 이 경계로만 성공/실패를 가른다.

3. 희생형 collapse: entity.rules 또는 solution에 "충격파/붕괴 영향 범위(구역)와 안전 대피 완료 기준(예: 폭발 1턴 전 인접구역 밖)"을 필수 명시. GM은 그 기준으로 희생자 외 인원의 생존을 판정한다(자의 금지).
```

---

### G5. 협력 판정 공식
**포함 패치:** P6  
**severity:** HIGH  
**대상:** `TRPGGameManager.java` — `GM_SYSTEM_BASE` 판정 시스템 섹션  
**삽입 앵커:** `"### 판정 시스템\n스탯 대비 결과가 불확실한 행동에만 d20 판정."` 문장 직후 (line ~252)에 삽입

**삽입 텍스트:**
```
협력 판정: 주도자 d20 + 보조 1인당 +2(최대 +6).
```

---

### G6. comms_monitored — 도청 처리 통합 (대면 면제 포함)
**포함 패치:** P14, P18, P29, P31, P43, P44b  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `buildGmPrompt` 메서드 내 comms_monitored 주입 블록  
**삽입 앵커:** `"- 통신 도청 ★: 괴담이 플레이어들의 기기 통신을 엿볼 수 있다."` 문장 (line ~4481)을 아래 텍스트로 전체 교체

**교체 텍스트:**
```
- 도청 ★: 괴담은 기기 통신(통화·무전·메시지)뿐 아니라 ★대면 대화도 엿들을 수 있다 — 같은 zone 대면이 자동 안전채널이 아니다.
  ★안전한 전달은 '그 괴담의 감지 양식으로 인지할 수 없는 수단'일 때만 성립한다. 예: 청각·통신 기반 괴담에게는 종이에 글/그림으로 적어 보여주기·수신호가 안전(소리를 내지 않으므로). 반면 시야·전지(全知)·빙의형 괴담은 그런 시각적 수단도 인지한다. seed의 괴담 감지 양식(청각/시각/통신/전지/접촉 등)에 따라 어떤 채널이 안전한지 GM이 판정하라 — 무엇이든 자동 안전이 아니다. 플레이어가 '도청 대비 수단(필담·암호·차폐)'을 쓰면, 그 수단이 해당 괴담 양식 밖일 때만 효과를 인정한다.
  감시 통신/도청된 대화 1회당 오염도(timeline_change)+1 또는 적 위협단계 1상승 중 상황에 맞는 쪽을 적용한다.
  '핵심 정보' 정의: collapse_condition·weakness·exploit_path·loophole 직결 내용. 일반 위치·안부 대화는 오염도+1만 적용, 모방 트리거 미발동.
  핵심 정보를 감시 채널로 넘기면 괴담이 그 대처법을 인지하고 무력화를 시도한다. 단, 선제 차단·모방 대응은 감청 턴 직후 다음 1턴 이내에 발동(즉시 텔레포트식 과반응 금지). 플레이어에게 1턴의 대응 여지를 남긴다.
  미래 통신 복수 채널: constraints.notes에 "감청 대상 채널 = [목록], 안전 채널 = [목록]" 명시 시, 각 채널에 맞춰 적용한다. 안전 채널이 명시된 경우 그 채널을 찾아 핵심 정보를 안전 전달하는 공략을 허용한다.
```

---

### G7. NPC schedule will=반응 — 타이밍·엄밀화·보조트리거·duration 통합
**포함 패치:** P5, P9, P11, P17, P21, P33  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `buildNpcSystemPrompt` 메서드 내 schedule 출력부  
**삽입 앵커:** `"- 의지가 '강함'이면 막혀도 다른 방법으로 재시도하고, '약함'이면 제지·설득에 포기한다."` 문장 (line ~3740) 직후에 삽입

**삽입 텍스트:**
```
반응(will=반응) 처리 규칙:
1. 발동 조건: condition에 명시된 구체적 행위(예: "특정 물건을 건드림"·"이름을 직접 물음")가 실제 일어난 직후 다음 1턴 안에 발동한다. 단순 접근·방문·같은 zone 진입만으로는 발동하지 않는다. condition 문구는 '구체적 행위'로 작성하며, 모호한 상태 기술("방문하면") 금지.
2. 보조 트리거(진행 보장): condition이 N턴 경과 시까지도 충족되지 않으면 NPC가 먼저 다가와 핵심 정보의 일부라도 전달한다. condition 충족 시 = 더 좋은 결과(전체 정보·최적 반응). 시간 트리거 발동 시 = 최소 보장(단편 정보·불완전 협력).
3. duration_turns: 있으면 "이 행동/상태는 N턴 지속" 주입. N턴 종료 후 after_duration이 있으면 "N턴 후 → [after_duration]"으로 주입. after_duration은 해당 NPC AI가 자신의 동기에 따라 자율 결정·실행하고, GM은 그 결과를 다음 장면 서술에 자연스럽게 녹인다(GM이 임의로 NPC 행동을 대신 정하지 않는다).
```

**추가 대상(생성 단계):** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` npc_schedules 설계부 (line ~321~326)  
**삽입 앵커:** `"will: \"강함\".../ \"반응\"(플레이어 행동에 촉발 — condition 필드로 조건 명시)."` 문장 직후에 삽입

**삽입 텍스트 (생성 스키마 확장):**
```
schedule 항목 선택 필드:
- duration_turns: 이 행동/상태가 지속되는 턴 수 (정수, 생략 가능).
- after_duration: duration 종료 후 상태("복귀"/"대기"/"도주" 중 하나, 생략 가능).
```

---

### G8. mislead 단서 설계 — 창작·친숙 모드 통합
**포함 패치:** P8, P10, P22, P34  
**severity:** MED  
**대상:** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` clues 설계부  
**삽입 앵커:** `'"clues": []'` 스키마 설명 근방 또는 ## 필수 설계 항목 '9. 배치된 단서 종류와 위치' 문장 직후에 삽입

**삽입 텍스트 (창작 모드 — 범용):**
```
★ clues 설계 규칙:
clues에 type="mislead" 단서 1~2개를 필수 포함한다. mislead 단서는 GM이 '그럴듯한 오답 방향'으로 자연스럽게 흘린다.
발동 타이밍: 진짜 단서가 2개 이상 확보된 뒤 자연스럽게 오답임이 드러나게 한다(처음부터 거짓 티 내지 마라). 플레이어가 mislead를 신뢰해 실제 행동(잘못된 차단·봉쇄 등)을 한 번은 실행해보도록 유도한 뒤 오답을 드러내라.
```

**삽입 텍스트 (친숙 모드 — generateFamiliarConcept clues 생성, P22·P34):**
```
★ 친숙 모드 mislead 규칙:
mislead 단서의 variant_basis는 해당 전설의 실제 채록 이형(지역별·시대별 구전 변형 버전)을 근거로 삼는다. variant_basis에는 실제 채록자·연도·지역(또는 명확한 구전 계통)을 포함한다. 확실한 실제 출처를 못 대면 mislead를 "같은 전설의 다른 지역 변형" 수준으로만 사용하고 가짜 학술 인용 금지. 목적: 원전을 아는 플레이어도 "어느 변형이 맞는지" 헷갈리게 만들어 지식이 함정이 되도록 설계.
```

---

### G9. 클리어/리트라이 상태 유지 (G1에 통합)
P16·P20은 G1 삽입 텍스트의 "리트라이 진입 시" 단락에 이미 포함. 별도 삽입 불필요.

---

### G10. GM 격리 태그 명시 의무
**포함 패치:** P39  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `GM_SYSTEM_BASE` 상수 내 통신/구역 서술 원칙 섹션  
**삽입 앵커:** `"### 협력·상호작용 원칙 ★ 최우선 (같은 위치 플레이어)"` 섹션 근방 또는 zone 서술 관련 문구 직후에 삽입  
**위치 확인 필요:** GM_SYSTEM_BASE 내 zone/comms 서술 관련 섹션 정확 위치 재확인 권장 (현재 파악된 섹션 구조상 line ~355~370 근방 추정)

**삽입 텍스트:**
```
★ 격리 서술 의무: 캐릭터가 통신두절·고립 구역에 들어가거나 나올 때마다 서술에 '[격리: <구역>·<시점>]'을 명시하라(평가의 소통불가 면제 판정 근거). 구간 종료 시 '[격리 해제]'도 남긴다.
```

---

### G11. 시간 가변 활용
**포함 패치:** P3  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `buildGmPrompt` 타임라인 시계 주입부  
**삽입 앵커:** `"- ★ 급박·위기 상황에서는 TIME_SKIP을 쓰지 마라(시간이 분·초로 천천히 흐른다). 스케일이 클수록 평온 구간의 도약을 크게 잡는다."` 문장 (line ~4523) 직후에 추가

**삽입 텍스트:**
```
- 탐색·대기·장면 전환 등 평온 구간에서는 적극적으로 <TIME_SKIP>으로 수십 분~수 시간을 건너뛰어라. 추격·대치 등 급박 구간에서는 1턴을 수 분 이내로 좁혀라. 매 턴 동일 간격으로 흐르게 두지 마라.
```

---

### G12. world_rules 은폐 지속
**포함 패치:** P2  
**severity:** MED  
**대상:** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` world_rules 설계부  
**삽입 앵커:** `"- hidden: 처음부터 아는가. 보통 true(모름) — 탐색·단서·희생으로 점차 파악."` 문장 (line ~232) 직후에 삽입

**삽입 텍스트:**
```
- hidden 규칙 운용: hidden=true이면 '결과(현상)'만 보여주고 메커니즘을 GM 서술이 깔끔히 확정·요약하지 않는다. 플레이어 가설은 '부분적으로만' 맞게 하고, 가짜 변수(인원수·타이밍 외 다른 요인처럼 보이는 것)를 한두 번 섞어 확신을 늦춰라.
```

---

### G13. NPC schedule duration_turns 스키마 (GDAM 생성)
**포함 패치:** P9, P11  
**severity:** MED  
**위치:** G7에서 이미 처리 (생성 스키마 확장 텍스트 포함). 별도 삽입 불필요.

---

### G14. 다수 critical NPC 조율 원칙 + 워치독 일시정지
**포함 패치:** P45, P46  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `GM_SYSTEM_BASE` 상수 내 GM NPC 조율 섹션  
**삽입 앵커:** `"### GM NPC 조율\n플레이어가 없는 배역은 GM이 직접 조종한다."` 문장 (line ~461) 직후에 삽입

**삽입 텍스트:**
```
★ 다수 critical NPC 조율 원칙:
- 같은 zone에 critical NPC가 2인 이상이고 motivation/knowledge가 충돌하거나 공유 필요가 있으면, GM이 두 NPC AI의 turn을 교대 서술해 자연스러운 정보 교환·충돌을 구현한다. NPC AI 컨텍스트는 분리돼 있으므로 GM이 교환된 내용을 양쪽에 명시 반영해 누락을 막는다(3인 이상이면 컨텍스트 부담↑ — 핵심 2인 상호작용에 집중하고 나머지는 배경 처리).
- NPC가 격리·기절·기억 소진·구속 등 '행동 불가 상태'에 있으면 무행동 워치독(N턴 미등장 강제 등장) 카운터를 일시 정지하고, 상태 해제 후 재개한다(행동 불가 NPC를 억지로 끌어내지 마라).
```

---

### G15. 번호 미보유 연락 시도 허용
**포함 패치:** P42  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `buildGmPrompt` 연락/통화 판정 섹션  
**삽입 앵커:** `"- 통신기기 불능: 전화·무전 등이 작동하지 않는다(신호 없음/시대상 부재)."` 문장 (line ~4479) 또는 통화 관련 주입 블록 말미에 삽입  
**위치 확인 필요:** phone_usable=false 조건 블록 이후 통화 허용 시 적용되는 문구 삽입 위치 재확인 권장

**삽입 텍스트:**
```
- 번호 미보유 연락 시도: 연락 대상 번호를 모르더라도 시도 자체를 허용하라. 단, 상황상 연결 불가 사유(인프라 마비·신호 차단·대상 도달 불가)가 있으면 '연결 실패 + 우회 단서(다른 경로 안내)'로 처리할 수 있다. 시도를 막지도, 성공을 강제하지도 마라.
```

---

### G16. hidden_info 반스포일러 강화
**포함 패치:** P41  
**severity:** MED  
**대상:** `TRPGGameManager.java` — `buildGmPrompt` 내 hidden_info 주입부 + `TraitManager.java` — `generateRoleTraits` system 프롬프트  
**삽입 앵커 (buildGmPrompt):** `"배역 독점 정보:"` 주입 문구 (line ~3613) 직전에 삽입  
**삽입 앵커 (TraitManager):** `"## 스포일러 금지 · 범용성 (★ 최우선)"` 섹션 (line ~167) 직후에 삽입

**삽입 텍스트:**
```
hidden_info에 해결 수단(아이템·경로·조작법)이 포함되면, 그 '존재·방법'만 알게 하고 '왜 필요한지(괴담 약점·해법과의 연결)'는 캐릭터가 인지하지 못한 상태로 설계하라. 정답을 처음부터 쥐여주지 마라(용도는 플레이 중 발견).
```

---

### G17. 챕터 분량·스케일/기간 분리
**포함 패치:** P15, P47  
**severity:** MED (P15) + LOW (P47)  
**대상:** `GdamGenerator.java` — `lengthGuideFor` 메서드 반환 문자열 (line ~1088) + buildGmPrompt zones 주입부  

**P47 텍스트 추가 위치:** `lengthGuideFor` 반환 문자열 말미 (line ~1092 이후)에 추가

**삽입 텍스트 (P47):**
```
  · 스케일(로컬~코즈믹)은 '사건의 영향 범위'로 정한다(단일 건물이라도 국가 기밀·핵심 인프라 위협이면 내셔널). 단 기간 span은 영향 범위와 분리 — 현장 집중형이면 수 시간~1일로 압축 가능(스케일 크다고 무조건 1주+ 강제 금지). 큰 TIME_SKIP은 이야기상 자연스러울 때만.
```

**P15 zones 접근권 주입:** `buildGmPrompt` zones 주입 블록에서 zones 수 ≥ 6 또는 scale이 내셔널/글로벌일 때 조건 분기 추가  
**위치 확인 필요:** `buildGmPrompt` 내 zones 주입 정확 위치 (line ~4423 이후 NPC 숨은 역할 블록 이전) 재확인 권장

**삽입 텍스트 (P15, 조건부):**
```
구역 접근권 (zones 6개 이상 또는 스케일 내셔널/글로벌): {zone}=accessible_by 요약 2~3줄. 빈 accessible_by는 '전원 가능'으로 명시.
```

---

### G18. gated_zones 구역 잠금 처리
**포함 패치:** P13, P19  
**severity:** HIGH (P13) + MED (P19)  
**조건부:** 장비·인증 잠금이 있는 시나리오에서만 주입  
**대상:** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` constraints 스키마 (line ~243~261) + `TRPGGameManager.java` — `buildGmPrompt` 제약 주입 블록  
**삽입 앵커 (GDAM):** `"- notes: 위로 표현 못 한 기타 자연 제약을 짧게"` 문장 (line ~256) 직후에 삽입

**삽입 텍스트 (생성 스키마):**
```
- gated_zones: (선택) 장비·인증 잠금이 있는 구역 배열. 각 항목: {zone: "zone_id", requires: "필요 조건(장비명/인증)", bypass: "물리 우회 방법(없으면 생략)", bypass_dc: DC숫자(생략 가능)}.
```

**삽입 텍스트 (buildGmPrompt — constraints 주입 블록 말미):**
```
gated_zones가 있으면 각 항목에 대해 주입: "구역 [zone]: [requires] 필요. 우회: [bypass] (판정 DC [bypass_dc], 미정의 시 DC 15)". bypass 자체가 없으면 "우회 불가".
```

---

## C. 조건부(시나리오 유형 감지 시만) 패치 분리표

아래 패치는 해당 조건이 확인된 시나리오에서만 생성·주입한다. 범용 주입 금지.

| 패치 그룹 | 조건 | 적용 대상 | 핵심 내용 |
|-----------|------|----------|-----------|
| P4 | 소음 트리거 괴담 | GDAM_SYSTEM_PROMPT constraints.notes 설계 | 통화·무전 음성도 의도적 소음과 동등 취급 명시 |
| P22·P34 (G8 친숙) | 친숙(familiar) 모드 | generateFamiliarConcept clues 생성 | mislead = 실제 채록 이형·variant_basis 실제 출처 의무화 |
| P23·P35 (G4 의례) | solution이 의례·정확입력형 | GDAM_SYSTEM_PROMPT entity.solution/hidden_rules | 정확 조건·오류결과 구조적 명시·격노 즉발 시 대응창 최소 1개 |
| P24·P25·P26 (G3) | 단일 주체(_single_entity·npc_dependency=low) | GDAM_SYSTEM_PROMPT + GM_SYSTEM_BASE | collapse_condition 의무화·배역 2인 허용·AI 단계 엄수 |
| P27 | 과거 시대 + phone_usable=false | GDAM_SYSTEM_PROMPT constraints.notes 설계 | 대체 통신 수단명·도달 소요·발각 위험 수준 필수 명시. comms_monitored 대신 이 notes 기준 적용 |
| P28 | 순수 회피형(collapse 없음) | GM_SYSTEM_BASE 클리어 판정 | 탈출 인원 수 기반 등급 기준선: 전원=C 기본, 과반=D, 1~2인=D~E. 사망해도 동료 살린 개인은 별도 상향 |
| P29 | 미래 통신 복수 채널 | GDAM_SYSTEM_PROMPT constraints.notes + buildGmPrompt comms_monitored | 감청 대상 채널·안전 채널 목록 명시 (G6에 병합) |
| P30 | 역설형(이해/접근 시 악화) | GDAM_SYSTEM_PROMPT entity.rules 설계 | '이해 시도' 행동 유형 목록·단계 상승 조건 entity.rules에 명시 |
| P40 (G4 희생) | 희생형 collapse | GDAM_SYSTEM_PROMPT entity.rules/solution | 충격파 범위·안전 대피 기준 필수 명시 (G4에 포함) |
| P13·P19 (G18) | 장비·인증 잠금 구역 있음 | GDAM constraints 스키마 + buildGmPrompt | gated_zones[] 배열·bypass_dc 주입 |
| P44·P44b (G3) | can_impersonate=true | GM_SYSTEM_BASE 괴담 AI 운용 | 위장 4규칙·정보흡수 comm 로그 기준 (G3에 포함) |
| P49 | 가변 턴 + 시간기반 마감/무효 조건 공존 | GDAM_SYSTEM_PROMPT collapse_condition·main_events + GM 시간 판정 | 시간 마감은 '실제 경과 분'으로만 계산·턴 환산 금지(분 단위 명시). iter20 검증 PASS |
| P50 | 대규모 다구역 + 위치 의존 bypass | constraints.gated_zones + buildGmPrompt | remote_bypass(true/false) 필드·현장 필수 시 이동 비용 명시·급박 구간 이동만으로 기회 전소 금지. iter20 검증 PASS |
| P51 | 과거 대체통신 + NPC 수신 | GDAM constraints.notes (P27 보강) | NPC 수신 대체통신도 동일 도달소요·발각위험(대면 즉시와 구분). iter21 |
| P52 | 위치 의존 loophole(이의제기·정화) | world_rules.hidden/entity.solution (P23 연장) | loophole 유효 zone 명시(어디서나 가능 해석 방지). iter21 |
| P53 | comms_monitored × can_impersonate | GM_SYSTEM_BASE (P43 보완) | 빙의/현장 숙주 같은 zone이면 대면 발화도 직접 청취 가능(기기 도청 면제와 별개). 안전 대면=숙주 없는 zone. iter22 |
| P54 | 장기 도약 + 고정 마감/주기 | buildGmPrompt TIME_SKIP 착지 | 착지 시점을 seed 고정 마감(결산일·만월·기일)과 대조·잔여 명시. iter23, iter24 검증 PASS |

### ★ P55 (HIGH·범용·장기 타임라인) — 본 절 상단 G(클리어/상태) 그룹과 함께 우선 반영
- **대상**: GM_SYSTEM_BASE / buildGmPrompt 시간 흐름 + GDAM 생성(timeline 단계 effect)
- **삽입 텍스트(복붙)**: "장기 TIME_SKIP(일·주·월·년 단위) 도약 시, 그 기간 동안 괴담 규칙·환경에 따른 누적 변화(반복 피해·SAN 손실·오염도 확산·자원 소모·NPC 상황 악화)가 '실제로 진행됐음'을 전제하고, 도약 직후 상태를 seed 규칙 기반으로 수치와 함께 명시 반영하라. 도약 기간을 무피해·무변화로 리셋 처리하지 마라(단, seed에 명시된 안전지대 휴식은 예외)." + 생성 시 장기 timeline 각 단계 effect에 '경과 중 누적 변화' 요약 포함.
- iter23 도출 → iter24(9개월 절기 확산형)에서 2회 도약 누적 반영 검증 PASS.

---

## D. 코드 TODO (CODE-1~12) 착수 스펙 — 심각도순

### HIGH 우선 (즉시 착수 권고)

---

#### CODE-3 | 사망 캐리어 보상 완전 제외 버그
- **파일·메서드·위치:** `TRPGGameManager.java` — `grantClearTraitRewards` 메서드 (line 3006) — `.filter(playerData -> !playerData.isDead)` 조건
- **현재 동작(증상):** `isDead=true`인 플레이어는 평가 등급(S/A 캐리)에 무관하게 특성 보상 대상에서 완전 제외됨. 자기희생으로 동료를 살리고 S/A 등급을 받아도 다음 스테이지에서 보상이 없어, 사용자 의도("사망해도 캐리하면 S + 특성 보상")와 정면 충돌.
- **목표 동작:** isDead=true이더라도 playerGrades에서 S/A인 플레이어는 예외 보상 경로로 처리.
- **정책 결정 필요 (사용자 확인 선행):** 보상 이관 방식 선택 — 후보 (a) 다음 캐릭터에게 1회 이관(유산 포인트), (b) 동일 캐릭터가 다음 스테이지 부활 시 1회 추가 선택권. 정책 미결정 시 코드 구현 불가.
- **수정 스케치:** 정책 확정 후, `grantClearTraitRewards`에서 `.filter(!isDead)` 이전에 `isDead=true AND pGrade∈{S,A}` 플레이어를 별도 컬렉션으로 추출 → 선택된 정책(유산 포인트/이월)에 맞는 예외 처리 경로 추가.
- **severity:** ★HIGH

---

#### CODE-4 | weaknessBonus 기준선 협소로 취지 사문화
- **파일·메서드·위치:** `TRPGGameManager.java` — `computeWeaknessBonus` 메서드 (line 2268~2270) — `(10 - hpSanBase) / 2`
- **현재 동작(증상):** `hpSanBase ≥ 10`인 캐릭터(HP3+SAN7=10 포함)는 `weaknessBonus=0`으로 '시작파워 낮을수록 강한 보상' 취지가 사문화. 대부분 캐릭터(hpSanBase≥10)가 보정 혜택을 받지 못함.
- **목표 동작:** 기준선 확장으로 더 많은 약체 캐릭터가 혜택을 받도록.
- **수정 스케치:** 현재 코드 1줄 수정: `(10 - hpSanBase) / 2` → `(14 - hpSanBase) / 2`. 결과: hpSanBase=14→0, 12→1, 10→2, 8→3(상한 3 유지). `Math.max(0, Math.min(3, ...))` 래퍼는 그대로 유지.
- **severity:** ★HIGH (iter13에서 med→high 격상)

---

#### CODE-6 | ZONE_ISOLATION 격리 자동 로그 없음
- **파일·메서드·위치:** `TRPGGameManager.java` / `GameStateManager.java` — zone 진입/이탈 처리 로직 (zone 변경 시 eventLog에 기록하는 부분)
- **현재 동작(증상):** P36/P37/P39의 평가 소통불가 면제 판정이 GM 서술의 수동 '[격리: ...]' 태그에 의존. GM 서술 누락 시 억울한 감점 발생. 자동화 없이는 평가 공정성 보장 불가.
- **목표 동작:** 플레이어가 통신두절·고립 구역에 진입/이탈할 때 `GameStateManager`가 자동으로 ZONE_ISOLATION(격리)·해제 system 로그를 생성. `buildFullEvalLog()`가 이 로그를 포함해 평가 AI에게 전달.
- **수정 스케치:** (a) 격리 구역 판별 기준 확정(예: `outside_contact=false` 또는 `can_leave_scene=false`인 구역 진입 시). (b) `GameStateManager`의 zone 변경 처리(ZONE_UPDATE 처리 경로)에서 해당 조건 충족 시 `eventLog.add(EventLogEntry.system("[격리: "+zone+"·"+시점+"]"))`를 자동 기록. (c) 이탈 시 `[격리 해제]` 로그도 추가. (d) P36 평가 프롬프트가 이 로그를 면제 증거로 참조(G2 교체 텍스트와 연동).
- **severity:** ★HIGH

---

#### CODE-8 | E_END 강제 실패 종료 시 runScenarioEvaluation 미호출
- **파일·메서드·위치:** `TRPGGameManager.java` — `GameStateManager.java:fireDueEvents()` (line 310)에서 `endEventFired=true` 설정 후 GM 서술 배드엔딩만 발생. `onClearEnding` 미호출.
- **현재 동작(증상):** 타임라인 4단계 `is_end=true` 이벤트(E_END) 발화 후 GM이 배드엔딩 서술만 하고 `runScenarioEvaluation`이 코드상 실행되지 않음. 실패 회차는 평가도, 후일담도, 특성 보상도 없어 시스템 불완전.
- **목표 동작:** E_END 처리 경로에서 `onFailureEnding()` 신설 또는 기존 `onClearEnding`에 실패 경로 연결 → `runScenarioEvaluation(실패 등급 D~E)` + 제한적 특성 보상 + 후일담 공개가 정식 산출.
- **수정 스케치:** `endEventFired=true` 확인 시점(메인 루프 또는 별도 체크)에서 `onClearEnding("F", "타임라인 종료 — 미해결", false)` 호출 경로 추가. 또는 별도 `onFailureEnding()`을 신설해 `runScenarioEvaluation` + 후일담 공개를 연결. `onClearEnding`의 `Phase.CLEAR/GAMEOVER` 중복 방지 로직 확인.
- **severity:** ★HIGH

---

#### CODE-9 | handleNpcDirectComm 원격 NPC 통화 불가 구조
- **파일·메서드·위치:** `TRPGGameManager.java` — `handleNpcDirectComm` (line 3981) — line 3988~3990 zone 동일 조건 강제: `if (!senderPd.zone.isEmpty() && !senderPd.zone.equals(npcZone))`
- **현재 동작(증상):** `handleNpcDirectComm`이 `senderPd.zone == npcZone` 조건을 강제하여, `phone_usable=true`라도 다른 구역·외부 기관 NPC에게 전화로 연락 불가. 통화 기믹 시나리오(iter14 '사신의 교환원')의 핵심 구조적 한계.
- **목표 동작:** 플레이어가 기기(전화) 보유 + NPC에 `phone_number` 필드가 있으면 zone 무관 원격 통화 허용. 대면 행위(같은 zone)와 원격 통화를 코드 분기로 분리.
- **수정 스케치:** `handleNpcDirectComm` 진입 시 분기 추가 — (a) `senderPd.zone == npcZone`이면 기존 대면 처리. (b) zone이 다르고 `phone_usable=true`이고 NPC 객체에 `phone_number` 필드가 있으면 원격 통화 처리(동일 NPC AI 호출, 단 대면 보너스 없음). (c) zone이 다르고 통화 불가 조건이면 기존 "같은 위치에 없습니다" 메시지.
- **severity:** ★HIGH

---

#### CODE-12 | INSTANT_CLEAR 스테이지3+ 게이트 봉쇄 사전 경고 없음
- **파일·메서드·위치:** `TRPGGameManager.java` — `activateInstantClear` 메서드 (line 2369~2376)
- **현재 동작(증상):** `activateInstantClear`가 room≥3에서 `resolved=false → 다음 스테이지 진출 불가`를 발동 전에 경고하지 않아, 플레이어가 의도치 않게 진출을 봉쇄당함.
- **목표 동작:** room≥3 시 확인 dialog("이 특성은 생존 처리(해결 아님)입니다. 스테이지 N에서 다음 스테이지 진출이 불가합니다. 발동하시겠습니까?")를 삽입 후 사용자 확인 시에만 `onClearEnding("F", ...)` 호출.
- **수정 스케치:** `activateInstantClear`에서 `roomNumber >= 3`이면 `DialogManager`(또는 채팅 확인 방식)로 경고 메시지 + 확인/취소 버튼 제시 → 확인 시에만 기존 `onClearEnding("F", ...)` 경로 실행. 취소 시 특성 사용 취소(또는 uses 복구).
- **severity:** ★HIGH

---

### MED (다음 작업 사이클)

| CODE | 파일·메서드·라인 근방 | 현재 동작(증상) | 목표 동작 | 수정 스케치 | severity |
|------|---------------------|---------------|---------|------------|---------|
| CODE-5 | `TRPGGameManager.java` / `GameStateManager.java` — comm 로그 기록 로직 | 실패한 통신 시도(연결 불가)가 comm 로그에 기록되지 않아 평가 AI가 '시도조차 없음'으로 오인 가능 | 실패 통신 시도도 comm 로그에 "시도(실패): 대상·사유"로 기록, `buildFullEvalLog()`에 포함 | 통신 시도 실패 처리 경로에 `state.logComm(sender, target, message, failed=true)` 추가 | med |
| CODE-7 | `CharacterGenerator.java` — `applyAiAdjustment` (line ~538) vs `ensureSurvivalFloor` (line ~353) 호출 순서 | `applyAiAdjustment`가 `ensureSurvivalFloor` 이후 적용돼 AI 음수 조정이 생존 하한(HP/SAN≥1)을 다시 무너뜨릴 수 있음 | `applyAiAdjustment` 후 `ensureSurvivalFloor` 재호출 또는 내부에서 하한 보장 | `applyAiAdjustment` 호출 직후 `ensureSurvivalFloor(pd)` 재호출 1줄 추가 | med |
| CODE-10 | `TRPGGameManager.java` / `PlayerData` — `everKnownContacts` (UUID 기반, line ~886·4053~4112·4132) | `everKnownContacts`가 UUID 기반이라 NPC 연락처(문자열 ID) 저장 불가. CONTACT_REVEAL로 알게 된 NPC 번호가 리트라이 시 소실 | `PlayerData`에 `everKnownNpcContacts(Set<String> npcId)` 신설. CONTACT_REVEAL 시 npc_id 추가. `onRetry` 복구 | `PlayerData`에 `Set<String> everKnownNpcContacts = new HashSet<>()` 추가, CONTACT_REVEAL 처리 시 `pd.everKnownNpcContacts.add(npcId)`. `onRetry`(line ~886)에서 복구 | med |
| CODE-11 | `TRPGGameManager.java` — 무행동 워치독 (line 1857: `(curTurn - lastNpcBeatTurn) >= 4`) | 워치독이 NPC '행동 불가 상태'(격리·기절·기억소진 등)를 인지하지 못해 카운터가 그대로 증가, 행동 불가 NPC를 억지로 등장 유발 가능 | 행동 불가 상태 NPC가 있으면 `lastNpcBeatTurn`을 현재 턴으로 갱신해 워치독 카운터 초기화(일시정지 효과) | `fireDueEvents` 또는 워치독 체크 직전에 critical NPC 중 행동 불가 상태(`status∈{faint,puppet,incapacitated}`)인 것이 있으면 `lastNpcBeatTurn = curTurn`으로 갱신 | med |

---

### LOW (여유 작업)

| CODE | 파일·메서드·라인 근방 | 현재 동작(증상) | 목표 동작 | 수정 스케치 | severity |
|------|---------------------|---------------|---------|------------|---------|
| CODE-1 | `GdamGenerator.java` — `generateFamiliarConcept` (line 675~676) region 선택 로직 | `(roomNumber - 1 + roll) % WORLD_LEGEND_REGIONS.size()`로 roomNumber 의존 → 초반 인덱스 편향 가능 | region 오프셋을 roomNumber 무관 순수 무작위로 분리 | `roll`만으로 region 선택: `WORLD_LEGEND_REGIONS.get(roll)`. roomNumber 의존 제거 | low |
| CODE-2 | `TRPGGameManager.java` — `buildGmPrompt` room 분기 (line ~4427~4428) | P7의 "room≤2 생존판정 진출 가능" 문구가 수동 주입 의존 → 빠질 수 있음 | `buildGmPrompt`에서 room≤2일 때 특례 문장 자동 주입 | room 값 체크 후 `if (room <= 2) sb.append("- ★ 이 스테이지(room≤2)에서는 생존판정(resolved=false)도 다음 스테이지 진출이 가능하다.")` 자동 추가 | low |

---

## E. 적용 체크리스트

### 프롬프트 그룹 적용 후 회귀 확인 포인트

| 확인 항목 | 관련 패치 | 확인 방법 |
|----------|----------|---------|
| 재도전 후 S 등급 가능 여부 | G1(P1) | iter01 재현 시나리오 — S 조건 충족 시 A로 강등 안 됨을 확인 |
| 해결/생존 경계 일관성 | G1(P12) | 부분 collapse 상황에서 GM이 생존판정 선택하는지 |
| 희생 선택 GM 차단 금지 | G1(P38) | GM이 "못 한다"로 차단하지 않고 결과 서술하는지 |
| 리트라이 시 HP/SAN 유지 | G1(P16·P20) | 1차 실패 후 재진입 시 스탯이 초기화되지 않는지 |
| 소통불가 면제 판별 | G2(P36·P37) | '[격리]' 로그 있는 플레이어 미공유 감점 안 받는지 |
| INSTANT_CLEAR 일괄 하향 없음 | G2(P48) | F 클리어 시 발동자 외 플레이어 개인 행동으로만 평가되는지 |
| 단일주체 collapse 비어있지 않음 | G3(P24) | 스테이지3+ 단일주체 시나리오 생성 시 collapse_condition 채워지는지 |
| 위장 corruption 지연 · 2턴 쿨다운 | G3(P44) | can_impersonate=true 괴담이 위장 중 corruption 1단계 지연되는지 |
| 핵심 단서 2개 독립 경로 | G4(P32) | NPC 1명 사망해도 다른 경로로 정보 입수 가능한지 |
| 대면 대화 도청 비대상 | G6(P43) | comms_monitored=true여도 같은 zone 대면 발언이 도청 카운트 안 됨 |
| NPC 반응 단순 방문 미발동 | G7(P17) | 플레이어가 zone 진입만으로 will=반응 NPC가 발동하지 않는지 |
| NPC 보조 트리거 N턴 보장 | G7(P33) | 오래 지나도 condition 미충족 시 NPC가 먼저 최소 정보 전달하는지 |
| 행동불가 NPC 워치독 정지 | G14(P45) | 기절/격리 NPC가 4턴+ 미등장해도 강제 등장 안 되는지 |

### 코드 수정 후 검증 포인트

| 확인 항목 | CODE | 검증 방법 |
|----------|------|---------|
| 사망 S 캐리어 보상 지급 | CODE-3 | 희생으로 사망 + S 등급 → 다음 스테이지 유산/이관 보상 발생 여부 |
| weaknessBonus=2 for hpSanBase=10 | CODE-4 | HP3+SAN7 캐릭터 생성 후 클리어 시 avgBoost +2 보정 확인 |
| ZONE_ISOLATION 자동 로그 | CODE-6 | 격리 구역 진입 시 eventLog에 '[격리: zone·시점]' 자동 기록 여부 |
| E_END 후 runScenarioEvaluation 호출 | CODE-8 | 타임라인 4단계 is_end 발화 후 평가 결과·후일담 산출 여부 |
| 다른 zone NPC 전화 연결 | CODE-9 | phone_usable=true + NPC phone_number 있을 때 원격 통화 성공 여부 |
| INSTANT_CLEAR 스테이지3 경고 dialog | CODE-12 | room=3+ 시 확인 dialog 출력 → 취소 시 미발동 확인 |

---

---

## F. 연장분 추가 패치 (iter19~27, P49~P57 + CODE-13)

### 프롬프트 (C·D절 외 추가)
- **P51**(low, 과거×NPC): 대체통신은 수신자가 NPC여도 동일 도달소요·발각위험(대면 즉시와 구분). → GDAM constraints.notes.
- **P52**(low, P23 연장): 위치 의존 loophole(이의제기·정화)은 유효 zone 명시. → world_rules.hidden/entity.solution.
- **P53**(low, P43 보완): comms_monitored×can_impersonate — 빙의/현장 숙주 같은 zone이면 대면 발화도 직접 청취 가능(기기 도청 면제와 별개). 안전 대면=숙주 없는 zone. → GM_SYSTEM_BASE.
- **P56**(med, 다인 분기): 분기 선택 갈림 시 (1)전원합의→(2)행동 주도자→(3)협력판정(P6), 채택 근거 명시·미채택 의도 일부 반영. → GM_SYSTEM_BASE branches.
- **★P57**(high, 정보기록 UX): extractAndStoreInfo 태스크에 "같은 대상 단서는 '[○○에 대한 단서]' 헤더로 묶어 출력" 추가 + new_clue 스키마에 clue_subject 필드. → extractAndStoreInfo 태스크/GM_SYSTEM_BASE. ※CODE-13 동반 필요.
- (P49·P50·P54·P55는 위 C·D절에 이미 반영)

### 코드 TODO 추가
- **★CODE-13**(high, task #26·#27 근본): PlayerData.infoItems(List<String> 평탄) → Map<String,List<String>> infoGroups(LinkedHashMap) 그룹 구조 교체 + DialogManager 렌더를 헤더+들여쓰기로 변경. P57(프롬프트)와 함께 적용해야 "단서 파란색 몰아씀" + "전화번호·능력 사실 별도 섹션" 근본 해결.

---

*FINAL_PROMPT_PATCHES.md — 최종 갱신 2026-06-27 (27회 시뮬·2h 연장 + 사용자 교정 P58~P60 반영)*  
*(중간 경과 푸터 — 최신 집계는 문서 맨 끝 참조) 엔딩(G19)·평가형식(G20)·발동시점(G21)·단계 가변(G22)·해결 추상화(G23)·대규모탈출(G24)·어휘주석(G25)*

## ★ 사용자 교정 (P58~P60) — 위 G1·G2·G6 본문에 이미 반영됨
- **P58 (해결판정=collapse 충족 여부로만, 전원 생존 불필요)**: G1 (2)에 반영. 사망자 있어도 collapse 완전충족이면 해결판정 유지, 생존자 수는 등급에만.
- **P59 (죽음 3분류 + 혼자 사망 시 타임라인 가속)**: G1 (3)·G2 죽음 평가에 반영. 전략적 양도(배턴패스)는 트롤 아님·감점 금지. 혼자 죽으면 다음 spawn까지 TIME_SKIP 가속(CODE-14로 코드 보장 권장).
- **P60 (대면도 도청 가능·안전채널=괴담 감지양식 밖 수단)**: G6에 반영. + 생성 시 entity에 `perception`(감지 양식: 청각/시각/통신/전지/접촉) 필드 명시 필요. P43/P53 교정·흡수.

---

## G19. 엔딩·공개 개편 (P61~P63) — 사용자 교정 2
**severity:** HIGH (P61·P62) + MED (P63)
**대상:** `TRPGGameManager.java` — `onClearEnding` (괴담 정보 공개 + 후일담 서술) / `runScenarioEvaluation` 후일담 / `GdamGenerator` entity.name·이름 단서
**동반 코드:** CODE-15(발견 사실 추적), CODE-13(렌더)

**삽입 텍스트 (onClearEnding 공개·서술 프롬프트):**
```
★ 엔딩 공개 규칙 (재플레이 대비 — 정답 전부 공개 금지):
1. 괴담 정보는 '이번 플레이에서 플레이어가 실제로 알아낸 사실'만 공개한다. 미발견 항목은 '못 찾음'(또는 ???). 불확실하게 파악한 것은 단정 말고 추정형("~인 것 같습니다"). 형식:
   정체: <확인된 정체 또는 ???>
   개요: <플레이어가 파악한 개요(추정형 가능)>
   약점
   • <발견한 약점>
   • 못 찾음            ← 미발견 약점 슬롯
   해결방법: <발견 시 서술 / 미발견이면 '밝혀내지 못함'>
2. 괴담 이름: 게임 중 이름을 알아냈으면(언급·검색·기록 발견) 공개에 반영. ※이름을 알면 대처법·약점·관련 사건 단서 접근이 쉬워진다(런타임: 이름 발견 시 관련 단서 DC 완화·더 직접적 제시). 생성 시 이름을 즉시 알 수도/단서로 알아내야 할 수도 있게 설계하고, 이름 발견을 해결 단서의 열쇠로 연결.

★ 통합 엔딩 서술 (후일담):
- 전 플레이어를 '하나의 통합 서사'로 보여준다(개별 후일담 나열 금지).
- 메인 해결 주체(들) 중심으로 서술, 비(非)주체 캐릭터는 한 줄로 간략히.
- 미해결 위협·잠복 요소는 열린 결말(재플레이/속편 훅) 가능. 형식:
   <메인 엔딩 서술 — 해결 주체 중심>
   ○○는 아직 잠들어 있습니다 (남은 위협/열린 결말)
   △△와 □□는 영웅적인 행동으로 기억되었습니다 (주체 강조)
   -END-
- ※ per-player 기여도 평가(등급·하이라이트·감점)는 별개로 개별 유지. 통합되는 것은 '서사 후일담'.
```

**CODE-15 (HIGH):** 플레이 중 발견 사실(정체·약점 각 항목·해결법·이름)을 플래그로 추적해야 '발견분만 공개'가 가능. clues/infoItems '발견됨' 표식 + onClearEnding 필터 + DialogManager 렌더(CODE-13과 연계). ※`.java`.

---

## G20. 시나리오 평가 출력 형식·타이밍 (P64) — 사용자 교정 3
**severity:** HIGH
**대상:** `TRPGGameManager.java` — `runScenarioEvaluation` 출력 형식 프롬프트 + 평가 표시 스케줄러(CODE-16)

**삽입 텍스트 (평가 출력 형식 — 프롬프트):**
```
★ 평가 출력 형식: per-player로 '항목별 등급 줄 + 총합등급'을 출력한다.
- 각 항목 = '<구체적 행동/판단/결과 서술> <등급(S~F)>' 한 줄. 잘한 행동(S/A)과 못한 행동(E/F)을 섞어 나열.
- 항목들 뒤 '총합등급 : <등급>' (그 플레이어 종합 — 단순 평균 아님, 경중 반영: 본질 파악 S라도 전멸 유발 F면 총합 D 식).
- 전 플레이어 출력 후 맨 끝에 '종합평가: <팀 서사 총평>' + '총합등급: <팀 등급>'.
예:
   오세윤[플레이어1]
   약한 적들을 잔뜩 처리함 A
   합당성 없이 동료를 구속함 E
   괴담의 본질을 파악함 S
   괴담의 해결 방법을 잘못 파악해서 아군이 전부 사망함 F
   총합등급 : D
   (다음 플레이어…)
   종합평가: ~~~ ~ ~ ~ ~~~~
   총합등급: E
- 각 줄은 '한 줄=한 평가'로 원자적(줄 단위 스태거 출력용). 후일담(G19 통합 서사)은 평가와 별개 연출.
```

**CODE-16 (HIGH) — 평가 표시 타이밍:** 평가를 ★한 줄씩 순차 출력, 줄 사이 딜레이 = clamp(1초, 5초, 글자수 비례)·기본 ~2초/줄(짧은 줄 ≈1초·긴 줄 ≈5초). 기존 고정 5초 스태거를 길이 기반 가변으로 교체. ※`.java`(평가 출력 스케줄러).

---

## G21. 평가·공개 발동 시점 (P65) — 사용자 교정 4
**severity:** HIGH
**대상:** `TRPGGameManager.java` — 스테이지 종료 디스패처(onClearEnding / onRetry / 실패·스톱 종료 = CODE-8) 의 호출 조건

**발동 매트릭스:**
| 스테이지 종료 유형 | 시나리오 평가(G20/P64) | 괴담 공개(G19/P61)+통합 엔딩(P63) |
|---|---|---|
| **리트라이**(재도전) | ✅ 출력 | ❌ (시나리오 계속 — 공개 시 스포일) |
| **스톱**(중단·실패 종료) | ✅ 출력 | ✅ 공개 |
| **클리어**(해결·진출) | ✅ 출력 | ✅ 공개 |

**코드 흐름:** 스테이지 종료 디스패처가 세 경로 모두에서 `runScenarioEvaluation` 호출. 괴담 reveal + 통합 엔딩 서술은 **스톱·클리어 경로에서만** 호출(리트라이 경로는 평가만, reveal·엔딩 생략). → CODE-8(실패/스톱 종료 시 평가·공개 미호출)을 이 매트릭스로 확정: **스톱=평가+공개 둘 다 / 리트라이=평가만 / 클리어=평가+공개(진출 시 전원 부활)**.

---

## G22. corruption·timeline 단계 수 규모 가변 (P66) — 사용자 교정 5 ★프롬프트+코드 동반 필수
**severity:** HIGH
**문제:** 현재 생성이 corruption_behavior=0~4(5고정)·timeline=1~4(4고정). 규모 무관 동일 → 사건 다양성 제한.
**★중요:** 프롬프트(아래)만 고치면 무효 — 런타임이 `Math.min(4,…)`로 캡(GameStateManager:167·312, TRPGGameManager:4575, CorruptionManager:44 `>=4`). **CODE-17 동반 필수.**

**대상 1 (생성·프롬프트):** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` 스키마 템플릿(line 346 corruption_behavior·365~ timeline) + spawn 지침(line 149)
**삽입/교체 텍스트:**
```
★ 단계 수는 괴담 규모(scale)에 비례 가변 (고정 금지):
- corruption_behavior: 0부터 연속 키, 규모별 개수 — 로컬 3(0~2)·시티 4(0~3)·내셔널 5(0~4)·글로벌 6(0~5)·코즈믹 7~8(0~6/7).
- timeline 단계: 1부터 연속, 규모별 — 로컬 2~3·시티 3~4·내셔널 4~5·글로벌 5~6·코즈믹 6~8.
- main_events: 단계 수에 맞춰 더 많이(더 다양한 사건).
- spawn_timeline "타임라인 N단계"의 N은 그 시나리오 timeline 단계 수 범위 내(1~최대단계).
- lengthGuideFor(분량)와 같은 scale 축으로 동조.
```

**대상 2 (런타임·CODE-17, HIGH):** seed의 corruption_behavior 항목 수·timeline 단계 수를 읽어 `maxStage`/`maxCorruption` 동적 산출 후 모든 하드코딩 '4' 대체:
- `GameStateManager.advanceTimeline`: `Math.min(4,…)` → `Math.min(maxStage,…)`
- ensureStageByClock(1~4 최소보장)·E_END 브리지 `=4`(:312) → maxStage
- `TRPGGameManager` `getTimelineStage()>=4`(:4575 등) → `>= maxStage`
- `CorruptionManager.isSpecialReward` `level>=4` → maxCorruption 기준, level 초과 시 최고단계 클램프
- `parseSpawnStage` "타임라인 N단계" N>4 허용

---

## G23. 약점·해결 서술 추상화 — 플레이어가 '구조적 구멍'을 창안하게 (P67) — 사용자 교정 6
**severity:** HIGH
**문제:** iter19 잔고자 등 solution/exploit_path가 "정확히 3개 결절점에 자기참조 채권 동시 삽입" 식 ★단일 고정 절차로 과상세. 이러면 플레이어가 구조적 구멍을 스스로 창안할 여지가 사라지고 '유일한 정답'을 맞히는 게임이 됨. (기존 line 93·104 '절차·횟수·도구 나열 금지' 규칙이 일관 적용 안 됨)
**대상:** `GdamGenerator.java` — `GDAM_SYSTEM_PROMPT` weakness/solution/exploit_path/escape 설계부 (line 93·104 강화)
**삽입 텍스트:**
```
★ 약점·해결 서술 = '원리·급소'만, 정확한 절차는 금지 (플레이어가 창안하도록):
- weakness/exploit_path: 괴담이 '왜 취약한가'(구조적 원리·모순·급소)만 적는다. 정확한 수행 절차·횟수("3개에 동시")·도구·좌표를 나열하지 마라.
- solution/collapse_condition: '충족되어야 할 조건(결과 기준)'으로 적는다 — 예 "자기 참조로 등록 회로에 모순을 일으킨다". '그것을 이루는 유일한 정확 방법'으로 적지 마라. 같은 원리를 만족하는 여러 접근이 가능해야 한다.
- GM은 플레이어가 그 원리를 실제로 만족시키는 ★어떤 창의적 접근이든 성공으로 인정한다(예: 자기모순 유발 수단이 무엇이든 원리 충족 시 collapse). 단 '원리 충족 여부'의 경계는 GM이 판정 가능하게 명확히 둔다(모호 ≠ 추상).
- 예외: solution이 ★의례·정확입력형(이름·재료·순서가 본질)인 경우만 P23대로 정확 조건+오류결과를 명시한다. 그 외 '공략·원리형'은 본 추상화 규칙을 따른다.
```

---

## G24. 대규모 죽음-귀속 정보 · 스테이지3+ 탈출 장기 유지 · 특성 탈출 (P68) — 사용자 교정 7
**severity:** HIGH
**대상:** `GdamGenerator.java` GDAM_SYSTEM_PROMPT(내셔널+ clues·escape 설계) + `TRPGGameManager.java` GM_SYSTEM_BASE(런타임 탈출 판정)
**삽입 텍스트 (생성):**
```
★ 스케일 내셔널 이상: '죽으면서만 얻을 수 있는 정보(death-gated clue)'를 간혹 배치(죽음=발견 → 다음 회차 활용). 이런 시나리오는 적극적 리트라이가 정공법 — 다회차 기억(오염도)으로 정보 이월.
★ 스테이지 3+에서 괴담 해결이 불가능해진 경우: 탈출 경로/생존 가능성(escape)을 꽤 오래 열어둔다(즉시 전멸 강요 금지 — 최소 1인이 살아남아 재도전 자격을 얻을 시간 보장). escape에 '오래 유지되는 탈출 창' 명시.
```
**삽입 텍스트 (런타임 GM):**
```
★ 플레이어 특성·능력을 이용한 창의적 탈출을 인정하라. 예: '원하는 만큼 시간정지' 능력으로 모든 재난이 끝날 때까지 영구 동결 → 탈출/생존 성립. 능력의 논리가 성립하면 막지 마라(P38·P67과 같은 결).
```

## G25. GM 서술 어휘 수준 + 어려운 단어 *주석 (P69) — 사용자 교정 7
**severity:** MED
**대상:** `TRPGGameManager.java` GM_SYSTEM_BASE 서술 문체 원칙 + 렌더(CODE-18)
**삽입 텍스트 (형식·프롬프트):**
```
★ 서술은 플레이어가 쉽게 이해할 수준의 어휘로 쓴다. 어려운 한자어·전문 용어·고유 명칭이 불가피하면 그 단어 뒤에 *를 붙이고 다음 줄에 '*<그 단어> : <쉬운 풀이>'를 단다(키는 '주석'이 아니라 해당 단어). 예:
   잔고자가 인과(因果)*를 청산하기 시작했다.
   *인과 : 원인과 결과
여러 어려운 단어는 줄을 나눠 각각 '*<단어> : <풀이>'(예: *인과 : 원인과 결과 / *청산 : 빚을 갚아 정리함). 괴담 정체 공개(G19/P61) 시 이름이 어려운 한자어면 그 뜻 풀이도 같은 형식.
```
**CODE-18 (MED) — 주석 회색 렌더:** 본문 내 '*' 마커와 '*<단어> : <풀이>' 주석 줄을 ★회색(§7 / NamedTextColor.GRAY)으로 출력해 본문과 시각 구분. 렌더러가 '*'로 시작하는 주석 줄을 회색 처리(CODE-13 그룹화 렌더와 같은 색상 계층). ※`.java`.

---

*FINAL — 최종 갱신 2026-06-27 (사용자 교정 P58~P69 전수 반영)*
*프롬프트 패치 P1~P69(+P44b) | 코드 TODO CODE-1~18 (HIGH 10: CODE-3·4·6·8·9·12·13·15·16·17) | 그룹 G1~G25*
