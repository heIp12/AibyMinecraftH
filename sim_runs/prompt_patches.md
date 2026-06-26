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
