# SCHEMA_DELTA.md — .gdam/런타임 신규·변경 필드 통합 (P1~P70 적용용)

코드 적용 시 ★생성 템플릿(GdamGenerator)·파서·런타임이 빠짐없이 다루도록, 패치들이 추가/변경한 필드를 한곳에 모음.
(작성 2026-06-27, 루프 27회 + 사용자 교정 P58~P70 기준. 플러그인 .java 미변경 — 적용 체크리스트용.)

---

## A. entity / world_rules

| 필드 | 위치 | 내용 | 패치 |
|---|---|---|---|
| `perception` | entity (또는 world_rules) | 괴담 감지 양식 배열: 청각/시각/통신/전지/접촉 등(복수 가능). 안전 채널·도청 대비수단 판정 근거 | P60 |
| `corruption_behavior` | entity.ai_context | ★개수 가변(고정 0~4 폐기): 0부터 연속 키, 규모별 3~8개 | P66 |
| `world_rules.collapse_condition` | world_rules | '충족 조건(결과 기준)'으로 기술, 정확 절차·횟수 금지(공략형). 의례형만 정확조건 명시 | P58·P67·P23 |
| weakness / exploit_path / solution / escape | entity | 원리·급소만(공략형). escape는 '오래 유지되는 탈출 창' 명시(스테이지3+) | P67·P68 |

## B. timeline

| 필드 | 내용 | 패치 |
|---|---|---|
| timeline 단계(1~N) | ★개수 가변(고정 1~4 폐기): 1부터 연속, 규모별 2~8 | P66 |
| `main_events` | 단계 수에 비례해 더 많이(다양한 사건). 시간 기반 마감은 '분' 기준 명시 | P66·P49 |
| `main_events[].branches` | auto{condition,next} + intervene[{condition,next}] (다중 분기 권장) | P24(기존)·P56 |
| 시간 마감/무효 조건 | '실제 경과 분'으로 계산(턴 환산 금지) | P49 |

## C. constraints

| 필드 | 내용 | 패치 |
|---|---|---|
| `comms_monitored` | 도청 발동(통화 1회당 오염도/위협+1·핵심정보 모방·다음1턴 차단). ★대면도 도청 가능 | P14·P18·P31·P60 |
| `gated_zones[]` | {zone, requires, bypass, `bypass_dc`, `remote_bypass`} 장비·인증 잠금 | P13·P19·P50 |
| `notes`(과거 시대) | 대체통신 수단명+도달 소요+발각 위험(NPC 수신도 동일 지연) | P27·P51 |
| `notes`(미래 복수채널) | 감청 대상 채널 / 안전 채널 목록 | P29 |

## D. roles (배역)

| 필드/속성 | 내용 | 패치 |
|---|---|---|
| hidden_info | 해결 수단 포함 시 '용도 미인지' 상태로. ★괴담 연루(씌인 피해자/괴담 주체) 사실도 hidden_info에 은닉(본인 미인지) | P41·P70 |
| 괴담 연루 배역 | ①씌인 피해자=큰 접근권 '키' ②플레이어=괴담 주체(본인 모름, 자기 해결). 해결 경로 필수 | P70 |
| 시작파워(hpSanBase) | 차등(약체일수록 강한 특성 보상) | (기존)·CODE-4 |

## E. npcs[].schedule

| 필드 | 내용 | 패치 |
|---|---|---|
| `will: 반응` condition | 구체적 행위만(단순 방문 금지) + N턴 경과 보조 트리거 | P5·P17·P33 |
| `duration_turns` | 행동/상태 지속 턴(선택) | P9 |
| `after_duration` | 지속 종료 후("복귀"/"대기"/"도주", 선택), NPC AI 자율 | P11·P21 |

## F. clues

| 필드 | 내용 | 패치 |
|---|---|---|
| type `mislead` | 1~2개 필수, 진짜 단서 2개 후 발현 | P8·P10 |
| `variant_basis` | (친숙 모드) 실제 채록 이형 출처(채록자·연도·지역) | P22·P34 |
| `clue_subject` | (정보 자동기록 그룹화용) 단서 출처 대상 태그 | P57 |
| 이름 단서 | 괴담 이름(entity.name) 발견 단서 → 대처법·관련사건 접근 키 | P62 |
| 발견 플래그 | 정체/약점 각 항목/해결법/이름 '발견됨' 추적(엔딩 발견분만 공개) | P61·CODE-15 |

---

## G. 런타임 동작(코드) 변경 요약 — 적용 시 함께

| 코드 TODO | 내용 | 심각도 |
|---|---|---|
| CODE-17 | 단계 수 하드캡(4) 전부 maxStage/maxCorruption 동적화(P66) — advanceTimeline·E_END·등급표 2단계·parseSpawnStage·isSpecialReward | HIGH |
| CODE-15 | 발견 사실 추적(엔딩 발견분만 공개 P61) | HIGH |
| CODE-13 | infoItems 그룹 자료구조 + 헤더 렌더(P57·#26·#27) | HIGH |
| CODE-8 | E_END/스톱 종료 시 평가 호출 + 공개(P65 매트릭스, #13) | HIGH |
| CODE-3 | 사망 캐리어 보상 이관(정책 D-1 후) | HIGH |
| CODE-6 | ZONE_ISOLATION 자동 로그(평가 공정성 P36/P37) | HIGH |
| CODE-9 | 원격 NPC 통화(같은 zone 강제 해제) | HIGH |
| CODE-12 | INSTANT_CLEAR room≥3 사전 경고 dialog | HIGH |
| CODE-16 | 평가 줄당 1~5초 가변 출력(P64) | HIGH |
| CODE-18 | 주석(*) 회색 렌더(P69) | MED |
| CODE-14 | 혼자 사망+대기자 있으면 종료/재도전 보류(P59) | MED |
| CODE-4 | weaknessBonus 기준선 확장 | MED(→HIGH 격상) |
| CODE-5·7·10·11·19 | 실패통신 로깅·생존하한 순서·NPC연락처 everKnown·워치독 행동불가 인지·(저우선)주체 무의식행동 | MED/LOW |

## H. 결정 (코드 적용 전)
- **D-1 (CODE-3) — ✅확정:** 스테이지 클리어 시 ★사망 무관 전원 무조건 보상 지급. isDead 필터 제거. (이관 정책 불요)
- **D-2 (P66/CODE-17) — ✅확정(기본값):** S등급 "빨리 해결" 임계값을 ★"전체 timeline 단계의 절반(올림) 이하"로 자동 비례(4단계→2, 6→3, 8→4). 코드가 maxStage 기반 계산.
- **D-3:** 적용 범위/순서 — 프롬프트만 먼저 vs 프롬프트+코드(HIGH) 동시? (사용자 확인 대기)

## I. 적용 후 필수
- 실제 컴파일 + 플레이테스트(루프는 프롬프트 시뮬만, 런타임 미검증).
- 스키마 변경 → 기존 .gdam/.replay 호환성 점검(구버전 5/4 고정 데이터 로드 시 maxStage 기본값 처리).
