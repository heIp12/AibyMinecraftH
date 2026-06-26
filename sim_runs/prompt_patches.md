# 프롬프트 개선 패치 (작업본 — 실제 플러그인 .java 는 절대 수정하지 않음)

시뮬레이션으로 발견한 프롬프트 약점의 개선안을 누적한다.
시뮬 에이전트는 소스의 '실제 프롬프트'를 베이스로 읽고, 그 위에 아래 패치를 덮어 적용한다.
형식: [대상] / [문제] / [추가·수정안].

---

## iter01 (#SIM3-K7P2, 스테이지3 시티) 발견 패치

### P1. 클리어 등급 — 재도전이 등급을 낮추지 않는다 (severity: high)
- [대상] GM_SYSTEM_BASE 클리어 판정 / grade 기준
- [문제] GM이 "재도전 1회 거쳤으니 S 대신 A"로 임의 강등. 실제 규칙엔 재도전 감점 조항이 없고, 오염도는 보상 등급을 '상향'만 함(getRewardGrade). S 조건(해결판정+전원생존+타임라인2단계이하)을 다 충족했는데도 강등됨.
- [수정안] 클리어 등급은 '최종 성공 회차의 상태'만으로 판정한다. 재도전 횟수 자체는 등급을 낮추지 않는다(오염도는 시스템이 보상에 별도 반영). 추가 문장:
  "★ 등급은 최종 성공 시점의 조건(해결/생존·생존자 수·타임라인 단계)만으로 매긴다. 재도전을 거쳤다는 이유만으로 등급을 내리지 마라."

### P2. world_rules 은폐 지속 — 규칙을 너무 빨리 '확정'시키지 말 것 (severity: med)
- [대상] GM_SYSTEM_BASE 세계관 규칙 / world_rules.hidden 런타임 지침
- [문제] 괴담 2~3턴 만에 NPC·플레이어가 "공유하면 사라진다"를 명확히 언어로 확인. 규칙의 미스터리가 너무 빨리 소진됨.
- [수정안] hidden 규칙은 '결과(현상)'만 보여주고 메커니즘을 GM 서술이 깔끔히 확정·요약해주지 않는다. 플레이어 가설은 '부분적으로만' 맞게 하고, 가짜 변수(인원수·타이밍 외 다른 요인처럼 보이는 것)를 한두 번 섞어 확신을 늦춰라.

### P3. 시간 가변 실제 활용 — 균일 진행 금지 (severity: med)
- [대상] GM_SYSTEM_BASE 시간 흐름 가변 / buildGmPrompt TIME_SKIP
- [문제] 전 구간 20분/턴 균일 진행. 평온 구간 TIME_SKIP도, 위기 구간 분·초 정밀도 실제로 안 쓰임.
- [수정안] 강화 문장: "탐색·대기·장면 전환 등 평온 구간에서는 적극적으로 <TIME_SKIP>으로 수십 분~수 시간을 건너뛰고, 추격·대치 등 급박 구간에서는 1턴을 수 분 이내로 좁혀라. 매 턴 동일 간격으로 흐르게 두지 마라."

---

## iter02 (#VY8K-3PMQ, 스테이지1 로컬·영역형 고립) 발견 패치
(P1~P3 모두 유효 확인 — 재도전 강등 없음 / world_rules 은폐 지속 / 시간 가변 활용)

### P4. 침묵·소음 제약에서 @통화 소음 처리 명시 (med)
- [대상] constraints.notes / GM 소음 판정
- [문제] 소리내면 봉쇄되는 괴담에서 @통화 음성이 '소음'으로 판정되는지 불명.
- [수정안] 소음 트리거 괴담의 constraints.notes에 "통화·무전 음성도 의도적 소음과 동등 취급" 조항 추가.

### P5. NPC schedule 'will:반응' 발동 타이밍 (med)
- [대상] buildNpcSystemPrompt schedule 출력부
- [문제] will=반응 항목에 발동 시점 지침이 없어 GM이 임의 발동.
- [수정안] schedule 출력 직후 한 줄: "반응(will=반응)은 그 condition이 실제 발생한 직후 다음 1턴 안에만 발동한다."

### P6. 협력 판정 합산 공식 부재 (high)
- [대상] GM_SYSTEM_BASE 판정 시스템
- [문제] 여러 명 협력 시 판정 합산 방식 미명시 → GM마다 다름.
- [수정안] 판정 섹션에 "협력 판정: 주도자 d20 + 보조 1인당 +2(최대 +6)" 공식 한 줄 추가.

### P7. 스테이지1~2 생존판정 진출 특례를 CLEAR reason에 명시 (low)
- [대상] buildGmPrompt 난이도 섹션
- [문제] room≤2 생존판정 진출 가능이 GM 서술/CLEAR에 자동 반영 안 됨.
- [수정안] "room≤2면 생존판정(resolved=false)도 진출 가능임을 CLEAR reason에 명시" 한 줄 추가.

## iter03 (#NX4T-7WRA, 스테이지5 내셔널·'조율사') 발견 패치
(P1·P3·P4·P5·P6 검증 완료 / P2 일부 미흡 — 가짜 변수 미삽입 / P7 범위 외)

### P8. P2 강화 — 가짜 단서(mislead)를 생성에서 필수화 (med)
- [대상] GDAM_SYSTEM_PROMPT clues 설계 / P2 보강
- [문제] world_rules 은폐용 '가짜 변수'를 GM 자율에 맡기니 실제로 안 들어감(P2 취지 미달).
- [수정안] clues 배열에 type "mislead" 단서 1~2개를 필수 포함. GM은 이를 '그럴듯한 오답 방향'으로 흘려 규칙 확신을 늦춘다.

### P9. NPC schedule 지속 턴(duration_turns) 명시 (med)
- [대상] buildNpcSystemPrompt schedule / 스키마
- [문제] critical NPC의 상태(예: 균열·동요)가 몇 턴 지속되는지 GM 컨텍스트에 없음 → 임의 처리.
- [수정안] schedule 항목에 선택적 duration_turns 필드. buildNpcSystemPrompt가 있으면 "이 행동/상태는 N턴 지속" 한 줄로 주입.

### (시나리오 특화 — 범용 패치 아님, 기록만)
- ISSUE-1/3 '합의 형성=괴담 트리거' 계열은 '조율사'류 시나리오에서만 유효. 범용 GM_SYSTEM_BASE에 강제 주입하면 다른 괴담을 왜곡하므로 패치화하지 않음. 단, 해당 괴담이면 entity.rules에 자체 명시하도록 생성 단계에서 처리(이미 entity.rules 자유 서술로 가능).

## iter04 (#QR5M-2HBK, 스테이지2 시티·1963년 과거) 발견 패치
(P1·P2·P3·P5·P6·P7·P8·P9 검증 완료 / P4는 phone_usable=false라 대상 없음)

### P10. mislead 단서 발동 타이밍 (med, P8 보완)
- [대상] GDAM_SYSTEM_PROMPT / GM mislead 운용
- [문제] 가짜 단서가 언제 오답으로 드러나야 하는지 지침 없음.
- [수정안] "mislead 단서는 진짜 단서 2개 이상 확보된 뒤 자연스럽게 오답임이 드러나게 한다(처음부터 거짓 티 내지 말 것)."

### P11. NPC duration 종료 후 행동(after_duration) (med, P9 보완)
- [대상] buildNpcSystemPrompt schedule / 스키마
- [문제] duration_turns 종료 후 NPC 행동 공백.
- [수정안] schedule 항목에 선택적 after_duration("복귀"/"대기"/"도주" 등). buildNpcSystemPrompt가 "N턴 지속 후 → after_duration" 한 줄로 주입.

### P12. 해결/생존 판정 경계 — 부분 collapse + 잔류자 (high)
- [대상] GM_SYSTEM_BASE 클리어 판정
- [문제] collapse_condition 부분 충족(예: 소각 성공)인데 잔류 피해자 발생 시 해결/생존 경계가 GM 재량에 과의존.
- [수정안] 기준선 한 줄: "collapse_condition 완전 충족 + 위협권 인원 전원 탈출/생존 = 해결판정. collapse 부분 충족이나 잔류·미구출 발생 = 생존판정(또는 등급 강등). 모호하면 생존판정 쪽으로."

### (기록만) phone_usable=false 시대의 대체 통신(쪽지/전령) 발각·도청 기준 — low, 시대 시나리오 한정.

## iter05 (#FW9L-4QZR, 스테이지4 글로벌·2054 근미래·'공명 잠식체') 발견 패치
(P1·P2·P3·P6·P8·P9·P11·P12 검증 완료 / P10 일부 심도 부족(가짜단서 너무 빨리 실증 해소) / P5 일부 미흡(반응 condition 느슨))

### P13. 장비·기술 착용 여부 분기 처리 지침 (high)
- [대상] constraints 스키마 / buildGmPrompt 제약 주입 / GdamGenerator constraints 규칙
- [문제] 근미래·특수장비 시나리오에서 특정 장비(뉴로링크 등) 착용 여부로 구역·패널 접근이 갈리는데, 미착용자가 잠금을 만났을 때 처리 기준이 GM 재량에 과의존. (현대 시나리오의 '열쇠/카드키/지문' 잠금도 동일 문제)
- [수정안] constraints에 선택적 `gated_zones` 배열 추가(각 항목 {zone, requires, bypass}). 생성 시 장비·인증 잠금이 있으면 채우고, buildGmPrompt가 "이 구역은 requires가 있어야 진입, 없으면 bypass 조건으로만 우회" 한 줄씩 주입. bypass 미정의면 "우회 불가(다른 경로 강제)".

### P14. comms_monitored=true 실제 발동 트리거 명시 (med)
- [대상] GM_SYSTEM_BASE 도청 / buildGmPrompt comms_monitored 주입
- [문제] comms_monitored=true여도 플레이어가 통신/감각공유 채널을 쓸 때 괴담이 '언제·어떻게' 반응하는지 정량 지침이 없어, 도청이 분위기 묘사로만 끝나고 실제 압박이 안 됨.
- [수정안] comms_monitored=true일 때 GM 지침 한 줄: "감시 통신(통화·무전·감각공유)을 1회 사용할 때마다 괴담이 그 내용을 인지하고, 오염도(timeline_change) +1 또는 적의 위협 단계 1상승 중 상황에 맞는 쪽을 적용한다. 핵심 정보를 감시 채널로 넘기면 괴담이 그 대처법을 따라 무력화를 시도한다."

### P15. 대규모(내셔널·글로벌) 스케일 zones 접근권 계층 GM 주입 (med)
- [대상] buildGmPrompt zones 주입부
- [문제] zones 8~10개 이상 대규모 시나리오에서 zones[].accessible_by·보안 레벨 계층이 seed에만 있고 GM 프롬프트엔 zone 이름만 들어가, GM이 "누가 어디에 들어갈 수 있나"를 임의 판정.
- [수정안] zones가 6개 이상이거나 scale이 내셔널/글로벌이면 buildGmPrompt에 "구역 접근권: {zone}=accessible_by 요약" 2~3줄 + (있으면)보안 레벨 계층 한 줄을 주입. 빈 accessible_by는 '전원 가능'으로 명시.

### P16. 리트라이 진입 시 이전 시도 부분 피해 유지 기준 (med, P12 보완)
- [대상] GM_SYSTEM_BASE 클리어/재시도 판정
- [문제] 1차 실패→리트라이 진입 시 이전 시도에서 입은 부분 피해(HP/SAN 손실, 경미 감염 등)가 유지되는지 초기화되는지 기준이 없어 GM마다 다름. ('다음 스테이지=전원 부활'과 혼동 위험)
- [수정안] 한 줄: "스테이지 내 재시도(리트라이) 진입 시 이전 시도의 부분 피해(HP/SAN 손실·상태이상)는 유지된다. 상태 초기화는 없다. (전원 부활은 '다음 스테이지 진출' 시에만 발생)"

### P17. NPC schedule will=반응 condition 엄밀화 (low, P5 보완)
- [대상] buildNpcSystemPrompt schedule / GdamGenerator NPC 규칙
- [문제] P5로 반응 '타이밍'은 잡혔으나, '발동 조건' 기술이 느슨하면(예: "방문하면") 단순 접근만으로도 반응이 터져 의도보다 쉽게 발동.
- [수정안] schedule will=반응 항목의 condition은 '구체적 행위/사건'으로 적게 한다(단순 위치 이동·방문 금지). buildNpcSystemPrompt 주입문 보강: "반응은 condition에 적힌 구체적 행위가 실제 일어난 직후 다음 1턴에만 발동하며, 단순 접근·방문만으로는 발동하지 않는다."

### (P10 보강 — 별도 패치화 없이 P10 문구에 합산 운용) mislead 단서는 진짜 단서가 충분히 모이기 전에 분기 실증으로 '너무 빨리' 해소되지 않게 한다. 가짜 단서를 신뢰한 플레이어가 실제 행동(예: 잘못된 차단·봉쇄)을 한 번은 실행해보도록 유도한 뒤 오답을 드러내라.

## iter06 (#MB7X-4QKN, 스테이지3 시티·현대·'부채무(負債霧)') 발견 패치
(P13·P14·P15·P16·P17·P10보강 전부 검증 완료 / P1·P6·P8·P12 확인. 신규는 모두 기존 패치의 빈틈 보완·범용)

### P18. comms_monitored '핵심 정보' 판별 기준 (med, P14 보완)
- [대상] GM_SYSTEM_BASE 도청 / buildGmPrompt comms_monitored 주입 (P14 문구에 합산)
- [문제] P14가 "핵심 정보를 감시 채널로 넘기면 괴담이 대처법을 모방"이라고 했으나, '핵심 정보'의 경계를 GM이 임의 판정 → 사소한 잡담까지 과반응하거나 반대로 무반응.
- [수정안] P14 문구에 정의 한 줄 추가: "여기서 '핵심 정보'란 collapse_condition·weakness·exploit_path·loophole에 직접 연관된 내용을 말한다. 일반 위치·안부 대화는 오염도 +1만 적용하고 모방 트리거는 발동하지 않는다."

### P19. gated_zones 우회 판정 DC를 buildGmPrompt에 함께 주입 (med, P13 보완)
- [대상] buildGmPrompt gated_zones 주입부 / constraints.gated_zones 스키마
- [문제] gated_zones[].bypass(물리 우회 등)의 성공 판정 DC가 seed에만 있고 GM 프롬프트에 안 들어가, GM이 우회 난이도를 임의 결정.
- [수정안] gated_zones 항목에 선택적 `bypass_dc` 필드. buildGmPrompt 주입 형식 보강: "구역 [zone]: [requires] 필요. 우회: [bypass] (판정 DC [bypass_dc], 미정의 시 DC 15)". bypass 자체가 없으면 "우회 불가".

### P20. 괴담 종속 축적값의 리트라이 유지 (med, P16 보완)
- [대상] GM_SYSTEM_BASE 클리어/재시도 판정 (P16 문구에 합산)
- [문제] P16이 HP/SAN만 명시 → 괴담 고유 축적값(채무 잔액·감염 수치·저주 스택·NPC 관계 변화 등)이 리트라이 시 유지되는지 불명.
- [수정안] P16에 한 줄 추가: "괴담 고유 축적값(채무·감염·저주 스택·오염도 누적·NPC 관계 등 시나리오 종속 수치)도 리트라이 진입 시 유지된다. 단 '다음 스테이지 진출' 시에는 전원 부활과 함께 초기화된다."

### P21. after_duration 실행 주체 명시 (low, P11/P17 보완)
- [대상] buildNpcSystemPrompt schedule after_duration 주입부
- [문제] duration_turns 종료 후 after_duration("복귀"/"도주"/"대기")을 NPC AI가 자율 수행하는지 GM이 서술로 처리하는지 불명 → 누락·중복 위험.
- [수정안] 주입문 한 줄: "duration_turns 종료 후 after_duration은 해당 NPC AI가 자신의 동기에 따라 자율 결정·연기하고, GM은 그 결과를 다음 장면 서술에 자연스럽게 녹인다(GM이 임의로 NPC 행동을 대신 정하지 않는다)."

## iter07 (#TK9R-2ELB, 스테이지2 시티·★친숙(familiar) 모드·El Silbón/베네수엘라) 발견 패치
(P1·P3·P6·P7·P8·P10·P12·P16·P20·P9·P11·P17·P21 전부 검증 완료 / P13·P14·P18·P19 대상없음(comms_monitored=false·gated 없음). ★친숙 모드 생성 경로 첫 검증 — 원전 충실도 4/5·게임화 4/5)

### P22. 친숙 모드 mislead는 '원전 이형(異形)·불분명 채록 버전'을 채택 (med, P8/P10 보완·친숙 모드 한정)
- [대상] GdamGenerator generateFamiliarConcept → .gdam 전개 / clues 설계
- [문제] 친숙 모드에서 mislead 단서를 평범한 오답으로 넣으면, 그 전설을 아는 플레이어에게 너무 쉽게 간파됨(El Silbón 시뮬의 '고추' mislead가 원전 지식 플레이어에게 취약).
- [수정안] 친숙 모드 clues의 mislead는 '해당 전설의 지역별 이형(異形)·구전 변형·불분명하게 채록된 버전'을 근거로 삼는다. 즉 "원전을 아는 사람도 어느 변형이 맞는지 헷갈리게" 만들어, 지식 자체가 함정이 되도록 설계. (창작 모드 mislead는 기존 P8/P10 유지)

### P23. 의례형 파훼(ritual-solution) 시나리오는 hidden_rules에 '정확 조건+오류 결과' 필수 명시 (med, 범용·의례형 한정)
- [대상] GDAM_SYSTEM_PROMPT collapse_condition/world_rules.hidden / GM 판정
- [문제] collapse_condition이 '정확한 의례 수행'(예: 정확한 이름 호명·재료·순서)인 시나리오에서, 정확 조건과 '틀렸을 때의 결과'가 hidden_rules에만 막연히 있으면 GM이 성공·실패 경계를 임의 판정.
- [수정안] solution이 의례·정확입력형이면 world_rules.hidden(또는 entity.solution)에 "정확 조건 = X(구체), 오류 시 결과 = Y(부분피해·오염도·리트라이 등)"를 반드시 구조적으로 명시. GM은 이 경계로만 성공/실패를 가른다(P12 해결/생존 경계와 연동).

### (기록만 — 코드 도메인, 프롬프트 외라 루프에서 미적용. 추후 .java 작업 시 반영 후보)
- CODE-1 (WEAK-03, low): generateFamiliarConcept의 region 선택이 roomNumber 의존이라 초반 인덱스 편향 가능 → region 오프셋을 roomNumber 무관 무작위로 분리(리팩토링). ※프롬프트 아님 → 본 루프 미적용.
- CODE-2 (WEAK-04, low, P7 보강): 스테이지1~2 생존판정 진출 특례가 CLEAR reason 수동 주입 의존 → buildGmPrompt가 room≤2일 때 특례 문장을 '자동' 주입하도록(코드). ※프롬프트 내용(P7)은 이미 충분; 자동화는 코드 TODO → 본 루프 미적용.

## iter08 (#LK3P-9TWM, 스테이지3 로컬·★단일 주체 괴담·조선 후기 1780s·보수귀) 발견 패치
(P1·P3·P6·P8·P10·P12·P16·P20·P9·P11·P17·P21·P23 전부 검증 / P13·P14·P18·P19·P22 대상없음. ★단일 주체 × 먼 과거 첫 검증 — 새 구조축에서 high 1건 재발견. '수렴' 판단은 보류)

### P24. 단일 주체 괴담의 해결(collapse_condition) 경로 보존 (★high, 범용·단일 주체형)
- [대상] GDAM_SYSTEM_PROMPT 스케일 너프 지침 / world_rules.collapse_condition 설계
- [문제] 생성 프롬프트의 스케일 초과 너프("해결 목표는 탈출·봉인 위주")가 단일 주체 괴담에서 collapse_condition 설계를 억제 → 단일 주체를 '탈출 전용'으로 만들면 스테이지3+ 게이트(해결판정 필수 진출)와 구조적 충돌(영영 진출 불가). 단일 주체여도 약점이 명확하면 소멸 경로가 있어야 함(쿠네쿠네式 순수 회피형은 예외로 허용하되, 그 경우 스테이지 배치를 1~2로 권고).
- [수정안] 한 줄: "단일 주체 괴담이라도 entity.weakness/exploit_path에서 논리적으로 도출되는 소멸·해제 조건이 존재하면 world_rules.collapse_condition을 반드시 채워라(탈출 전용으로 비우지 마라). 진짜로 해결 불가한 순수 회피형(쿠네쿠네類)이면 그 사실을 명시하고 스테이지 1~2 난이도로 배치 권고(스테이지3+는 해결판정 필수라 부적합)."

### P25. 단일 주체형 배역 분산 강제 완화 (med, 한정·단일 주체형)
- [대상] GDAM_SYSTEM_PROMPT 배역 설계 원칙 / meeting_design
- [문제] "핵심 배역 3~4개·각기 다른 시작 위치·구조적 만남 보장" 원칙이 단일 주체 시나리오에도 강제돼, NPC가 적어도 '배역(플레이어 캐릭터)' 자체가 미니 앙상블을 형성 → 단일 주체 특유의 고독·압박이 희석.
- [수정안] 단일 사물/단일 주체 괴담(npc_dependency=low, _single_entity)에 한해: "배역 2인 최소 운용 허용. 만남 창(meeting_window)을 늦게 설정 가능. 대신 단서 비대칭을 충분히 확보해 협력 동기를 유지하라." (다인 앙상블형은 기존 3~4 배역 원칙 유지)

### P26. 단일 독립 AI 운용 지침 (med, 범용)
- [대상] GM_SYSTEM_BASE 괴담 AI 서술 원칙
- [문제] entity.independent_ai=true·can_impersonate=false인 단일 주체를 GM이 어떤 원칙으로 서술할지(패턴 단조 회피·압박 단계 상승·정지 페널티 타이밍) 전용 지침이 없어, ai_context.corruption_behavior를 무시한 임의 서술 가능.
- [수정안] GM_SYSTEM_BASE에 단락 추가: "entity.independent_ai=true이고 can_impersonate=false인 단일 주체는 ai_context.corruption_behavior의 단계 순서를 반드시 준수하고, 한 응답에서 corruption 단계를 2단계 이상 건너뛰지 마라. 위협은 물리 현상·환경 변화로만 간접 표현하는 원칙을 단일 주체에서 특히 엄격히 적용한다(직접 묘사로 신비 소진 금지)."

### P27. 먼 과거 시대 대체 통신 판정 기준 명시 (med, 한정·과거 시대)
- [대상] GDAM_SYSTEM_PROMPT constraints / GM 통신 판정
- [문제] phone_usable=false인 과거(조선·중세·에도 등)에서 구전·서신·봉화·연기 신호 등 대체 통신의 도달 시간·발각 위험·신뢰도 기준이 없어 GM이 현대 통신처럼 즉시 취급하거나 완전 차단하는 양극단.
- [수정안] constraints.era가 과거이고 phone_usable=false이면 constraints.notes에 "대체 통신 수단명 + 도달 소요(예: '서신-하루 이상', '봉화-즉시이나 가시범위 한정') + 도청/발각 위험 수준"을 반드시 명시. comms_monitored 대신 이 notes 기준으로 도청·지연 판정(P14는 현대 감시통신용, 과거는 본 조항).

## iter09 (#HQ7R-3MVX, 스테이지1 로컬·★순수 회피형 단일 주체·먼 미래 2347 심우주·거리 역설자) 발견 패치
(★P24 예외(해결불가→collapse 비움·스테이지1 배치)·P25·P26·P14·P18 전부 ★통과 / P1·P3·P6·P7·P8·P9·P10·P11·P12·P16·P17·P20·P21 확인. 신규는 전부 niche 시나리오 한정 med/low — 새 high 없음)

### P28. 순수 회피형 탈출 인원 수 기반 생존판정 등급 기준선 (med, 한정·순수회피형)
- [대상] GM_SYSTEM_BASE 클리어 판정 / 순수 회피형 등급
- [문제] 해결 불가 순수 회피형은 '탈출=클리어'인데 부분 탈출(포드 일부·1~2인만 탈출) 시 등급 기준이 없어 GM 재량 과의존.
- [수정안] 순수 회피형(collapse 없음) 생존판정 등급 기준선 한 줄: "전원 탈출=C 기본(캐리·저피해면 상향 가능), 과반 탈출=D, 1~2인만 탈출=D~E. 단 사망자가 동료를 살리고 죽었으면 개인 등급은 별도 상향(캐리 평가)." (해결형 등급선과 구분)

### P29. 미래 통신 채널별 감청 대상 범위 명시 (med, 한정·미래통신)
- [대상] GDAM_SYSTEM_PROMPT constraints / comms_monitored 운용 (P14 보완)
- [문제] 미래 시설은 통신 채널이 여러 종(양자채널·광통신·함내 인터콤 등)인데 comms_monitored=true가 '어느 채널이 감청되는지' 구분 안 해, GM이 전 채널 감청/무감청 양극단.
- [수정안] comms_monitored=true이고 채널이 복수면 constraints.notes에 "감청 대상 채널 = [목록], 비감청(안전) 채널 = [목록]"을 명시. 안전 채널이 있으면 플레이어가 그것을 찾아 핵심정보를 안전 전달하는 공략이 가능해짐(P18 핵심정보 정의와 연동).

### P30. 역설형(이해/접근 시 악화) 메커니즘의 트리거 정량화 (low, 한정·역설형)
- [대상] GDAM_SYSTEM_PROMPT entity.rules / GM 판정
- [문제] '이해하려 하면 더 다가온다'式 역설형에서 '이해 시도' 행동의 경계가 모호해 GM이 임의 판정(질문만 해도 발동? 조사 행동만? 기준 불명).
- [수정안] 역설형 entity.rules에 "이해/접근 시도로 간주되는 행동 유형(예: 직접 관측·기록·말걸기·추적)과 각 단계 상승 조건"을 예시로 명시하도록 생성 지침 추가. GM은 그 목록 밖 행동은 트리거로 치지 않는다.

### P31. 감청 AI/적의 핵심정보 감청 후 선제 차단 타이밍 (med, 한정·감청형, P14/P17 연동)
- [대상] GM_SYSTEM_BASE comms_monitored 발동 (P14 보완)
- [문제] 감청하는 적이 핵심정보를 들은 뒤 '언제' 선제 대응(경로 차단·대처법 모방)을 하는지 불명(즉시 vs 다음 턴).
- [수정안] P14에 타이밍 한 줄 추가: "감청한 적의 선제 차단·모방 대응은 P17 반응 규칙과 동일하게 '감청한 그 턴 직후 다음 1턴 이내'에 발동한다(즉시 텔레포트式 과반응 금지). 플레이어에게 1턴의 대응 여지를 남긴다."

## iter10 (#RK7B-2WSL, 스테이지3 시티·★친숙 2회차·슬라브 루살카·의례형) 발견 패치
(★P22(원전 이형 mislead)·P23(의례 정확조건+오류결과) 둘 다 ★pass — 실제 채록 이형 아파나시예프(1872)·콜베르크(1880) 활용, 원전 지식자도 혼동. P10·P1·P3·P6·P8·P9·P11·P12·P16·P17·P20·P21 확인. ★해결 경로 강건성에서 범용 high 1건 재발견 — 미수렴)

### P32. 해결 경로(필수 단서·열쇠)의 단일 장애점 금지 (★high, 범용)
- [대상] GDAM_SYSTEM_PROMPT 단서·해결 경로 설계 / meeting_design
- [문제] collapse_condition 달성에 꼭 필요한 정보(예: 진짜 이름·정확 재료·열쇠 위치)가 '단일 출처(문서 1개 또는 NPC 1명)'에만 있으면, 그 출처가 막히거나 NPC가 죽으면 시나리오 전체가 소프트락(해결 불가→스테이지3+ 영구 진출 불가). iter10의 이름 확인이 문서+NPC 이중 잠금이라 단일 장애점에 근접.
- [수정안] 한 줄: "collapse_condition 달성에 필수인 핵심 단서·열쇠는 최소 2개의 독립 경로로 입수 가능하게 설계하라(문서/NPC/환경 단서 중 둘 이상). 하나가 막혀도(NPC 사망·구역 봉쇄) 대체 경로로 해결 가능해야 한다. 단일 출처면 그 출처의 보호·복원 수단을 함께 둔다."

### P33. will=반응 NPC의 미발동 방지 보조 트리거 (med, 범용, P5/P17 균형 보정)
- [대상] buildNpcSystemPrompt schedule will=반응 / 스키마
- [문제] P5·P17로 반응 condition을 엄격화한 부작용 — 플레이어가 '정확한 트리거 행위'를 끝내 못 찾으면 핵심 NPC 반응이 영영 안 터져 해결 정보가 잠김(특히 열쇠형 NPC). 엄격함과 진행보장의 균형 붕괴.
- [수정안] will=반응 항목에 선택적 보조 조건 병기 허용: "condition(구체 행위) 또는 N턴 경과 시 발동". buildNpcSystemPrompt가 "정확 조건 미충족이라도 N턴 지나면 NPC가 먼저 다가와 일부라도 전달"로 주입. 진행 잠금 방지(엄격 트리거는 '더 좋은 결과', 시간 트리거는 '최소 보장').

### P34. 친숙 mislead variant_basis의 실제 채록 출처 의무화 (med, 한정·친숙 P22 보완)
- [대상] GdamGenerator 친숙 모드 clues 생성 / P22 보완
- [문제] P22가 'mislead=원전 이형'을 요구하나, GM·생성이 variant_basis를 '창작 채록본'으로 지어내면 P22 취지(실제 이형의 혼동)가 깨짐.
- [수정안] 친숙 모드 mislead의 variant_basis에는 '실제 채록자·연도·지역(또는 명확한 구전 계통)'을 포함하도록 의무화. 확실한 실제 출처를 못 대면 그 전설은 mislead를 '같은 전설의 다른 지역 변형' 수준으로만 쓰고 가짜 학술 인용을 지어내지 않는다.

### P35. 격노형 의례 오류결과에 대응 행동 여지 부여 (med, 한정·의례 격노형)
- [대상] GDAM_SYSTEM_PROMPT entity.escape/hidden_rules / 의례 실패 처리
- [문제] 의례 정확조건 오류 시 '격노' 페널티가 즉발 강제 피해로만 처리되면 플레이어가 1턴간 아무 대응 못 하는 수동 구간 발생(재미·공정성 저하).
- [수정안] 의례 오류결과가 '격노·즉발 반격'형이면 entity.escape 또는 hidden_rules에 그 순간의 임시 대응 행동(도피 경로·연기 차단·미끼 등) 최소 1개를 함께 명시. GM은 오류 페널티와 동시에 그 대응 창을 제시한다(완전 무력 구간 금지)."
