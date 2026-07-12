# GDAM 생성 프롬프트 청크-모듈 재분할(②) — 설계·검증·구현

> **구현 완료(2026-07-12)**: 커밋 `31c3f15`(1/2 구조 재분할) + `869376d`(2/2 계약·가드). 아래 §9 구현 기록 참조.
> 실측: 폴백 `GDAM_SYSTEM_PROMPT` 126042B/sha256 `bfadd79a…` 불변 · SYS_STRUCT 68679B·WORLD 53785B·ROLES 32360B·ITEMS 14355B(합 169KB, 폴백4배 504KB 대비 **−66%**) · 스탠드얼론 75/75.


> 다중에이전트 Workflow(분석3 → 설계3+종합 → 적대검증) 산출. **비파괴 설계 단계 — 코드 변경 없음.**
> 적대검증 판정: **SOUND / goAhead:true / crossLeakFixed:true / orphanSections:[] / starvedChunks:[]** (단 아래 §5 필수 가드 이행 조건부).
> 관련 작업: #107(Step2 PromptBuilder 추출·CORE/모듈 조합식), #108(Step3 생성기 청크 분할). GPT #5(과부하) + 아이템청크 교차유출의 진짜 해결.

## 1. 문제 (현행)

`GdamGenerator.generateChunked`(~1684행)는 4개 생성 청크(struct→world→roles→items)를 순차 호출하는데,
**각 호출이 `GDAM_SYSTEM_PROMPT` 전체(1A+1B+2A+2B = 125,691B, 44개 섹션)를 통째로 시스템 프롬프트로 전달**한다.

- **과부하(GPT #5)**: 각 청크가 자기가 만들지 않는 필드의 규칙까지 통째로 받음. 1회 완전생성에 시스템프롬프트만 4×126KB≈504KB 재전송(주의분산·토큰비용).
- **교차유출(items 청크)**: 정작 필요한 데이터는 못 받음. `itemCtx`(~1799행)에 clues/world_rules/capstone/timeline/common_items가 빠져 있어:
  1. 글 아이템 content가 실존 단서와 **중복·모순** (스키마 409~416행 요구를 못 지킴)
  2. 해법·붕괴조건을 몰라 **collapse_condition을 사소하게 우회하는 '만능 아이템'** 생성
  3. capstone 종결단서(clue_hintgate)를 우회하는 **대체 해결 아이템** → 힌트관문 게이트 붕괴
  4. 사건 시점을 몰라 **획득 타이밍·start_item 오배치**(후반 정보를 초반 소지품으로)
  5. common_items를 못 봐 **'아이템 중복 방지 ★절대 준수'(454행)가 공염불**

## 2. 원칙

- **규칙은 모듈(정적 텍스트)로, 데이터는 계약(런타임 전달)으로.** 44섹션을 전수 매핑해 CORE(공통) + 청크별 MODULE로 재배치하되 **규칙 손실 0**(모든 섹션이 최소 1곳 잔존). 교차유출은 규칙 이동이 아니라 청크 간 데이터 계약으로 해소.
- **스포일러 방향성**: 파이프라인이 struct→world→roles→items **단방향**이고 정답 정보(hidden_rules·solution·exploit_path)는 최초 청크 struct가 자체 생성하므로 앞 청크로의 역류가 원천적으로 없음. 뒤 청크로 넘기는 데이터도 **'존재·조건·요지'만, 정답 절차(How)는 제외**.
- **공유 섹션은 단일 상수 다중 참조**(텍스트 복제 편집 금지 → 사본 드리프트 방지).

## 3. 재배치 (종합안)

### CORE (공통 — 4청크 전부에 실림, ≈9.2KB)
5개 섹션만: `[머리말] 역할정의` · `## 출력 규칙` · `## ★★ 언어 수준` · `## 출력 JSON 스키마 — 공통 봉투만`(seed/room/scale + 최상위 키 목록·타입 + 메타은닉 인라인 규칙) · `## ★★ 설계 정합성 최종 자기검증(8항)`.
> 필드별 서브트리 스키마는 CORE가 아니라 **각 청크 모듈이 자기 슬라이스로 보유**.

### MODULE_STRUCT (entity·world_rules·constraints·timeline, ≈67KB)
concept 원천 3섹션(괴담 이름·소재 다양성·설계 순서) 흡수(파이프라인에 concept 청크 없음, entity 생성 청크가 겸함). +
이야기 기승전결, 사건설계★(공유), 성격/행동양식/스케일/세력, 필수설계항목(공유), weakness/exploit_path/약점추상화/physical, world_rules★★, constraints, 타임라인 v2, 단계수가변(공유), can_impersonate/perception/hidden_rules, 해결경로강건성(공유), + struct 스키마 슬라이스.

### MODULE_WORLD (zones·relationships·npcs·clues·daily_prologue·meeting_design·common_items, ≈53KB)
사건설계★(공유), 필수항목(공유·항목9=단서 책임), 배역격/char_name/배역관계(공유·npcs 측면), zones/area/realm, npcs, common_items, clues★, 해결경로강건성(공유), 만남검증(공유), + world 스키마 슬라이스.

### MODULE_ROLES (roles[], ≈30KB)
배역설계원칙, 괴담연루배역, 배역격/char_name/배역관계(공유), job_pool/age_range, spawn_timeline, 단계수가변(공유), pre_spawn_beats, role_stats, 아이템설계규칙(공유·roles는 start_item 표기만), 필수항목(공유·항목10=핵심배역), 만남검증(공유), + roles 스키마 슬라이스.

### MODULE_ITEMS (key_items[], ≈15.5KB)
아이템설계규칙★(공유·전량), + items 스키마 슬라이스(참조용 clues·gated_zones id 필드).

> **공유 섹션 9개**(사건설계·필수항목·배역격·char_name/gender·배역관계·단계수가변·해결경로강건성·만남검증·아이템설계규칙)는 **동일 String 상수 조각을 여러 모듈이 `String.join`으로 참조**.

## 4. 데이터 계약 (6개)

| # | from → to | 전달 요약 | 상태 |
|---|---|---|---|
| 1 | struct → world | entity·world_rules·constraints·timeline (확정 데이터) | 기존 `structCtx`(1732행) 유지 |
| 2 | struct → roles | entity.rules·world_rules(core/loophole)·era·단계수 (규칙 요지) | 기존 `coreCtx`(1761행) 지분 |
| 3 | world → roles | npcRosterBrief(1768) + clueDigest(1769) | 기존 유지 **+ capstone 필터 추가 필요** |
| 4 | struct → items | itemCtx + **world_rules(loophole/collapse/solution 요지)·timeline 요약 신규** | 기존 itemCtx(1799) 확장 |
| 5 | **world → items (신규 — 교차유출 핵심)** | 단서 봉인 다이제스트: 각 clue의 id·type·capstone 여부·요지 1줄·requires_event_resolved(게이트 조건) + gated_zones id + common_items 목록. **존재·게이트 조건만, How 제외** | **신규 구현** |
| 6 | roles → items | start_item·NPC 소지자 char_name·직업·위치 | 기존 itemCtx의 roles 인자(1799) 유지 |

## 5. 적대검증 — 필수 가드 (착수 전 '권고' 아닌 '필수 구현 단계')

1. **world→items 다이제스트 신규 스포일러 벡터 방어**: key_items[].content는 플레이어가 초반 획득·열람 가능 → 다이제스트를 '요지 1줄·How 제외'로 제한 + **`item content ≠ capstone content` 회귀 lint 추가**.
2. **clueDigest capstone 필터 실제 구현**: 현행(1946행)은 무차별 `content[0:60]`을 roles에 투입 → 계약이 명시한 'capstone content 원문 제외'를 지키려면 코드에 capstone 필터를 실제로 넣어야 함(계약 문구만으론 안 바뀜).
3. **폴백·재시도 동반 수정**: 폴백 `generate()`(2252행)·roles 재시도(chunkArray 내부 1908행)가 아직 `GDAM_SYSTEM_PROMPT` 전체를 재사용 → 폴백=전모듈 결합본(=현 GDAM_SYSTEM_PROMPT 유지 가능), 재시도=CORE+MODULE_ROLES로 동반 수정. 누락 시 실패 경로에서 재분할 이득 소멸.
4. **스키마 슬라이싱 방어(최대 위험)**: 인라인 짝규칙 주석(main_events.unlocks_clue↔clues.id, gated_zones↔key_items.unlocks)이 필드 경계를 넘나듦 → **양쪽 슬라이스에 중복 잔존 + (전체 재조립 == 원본 바이트 동일성) 검증 + lintGdam 필드 커버리지 재검증**을 필수 단계로 강제.
5. **공유 섹션 단일 상수**: 9개 공유 섹션은 반드시 단일 String 상수 다중 참조(복제 편집 금지).
6. **CORE 적재 방식·토큰 수치 확정**: CORE(9.2KB)가 모듈 위 별도 적재인지 포함인지 코드에서 일관 확정, 절감 수치 실측 정정.

## 6. 효과 (추정)

1회 완전생성 시스템프롬프트 총량 현행 4×126KB≈**504KB → 합계 ≈165~202KB(약 −60~65%)**.
- items 126KB→≈15.5KB(**−88%**), roles→≈30KB(−76%), world→≈53KB(−58%), struct→≈67KB(−47%, entity/world_rules/constraints/timeline 규칙이 본질적으로 무거워 손실 없이 도달 가능한 하한).
- **부수 이득**: 섹션 단위 상수로 재분해하면 각 개별 String 리터럴이 최대 8085B(constraints)로 작아져, 65535B 리터럴 한계 압박(현행 1A 여유 ~1062B)이 근본 해소됨.

## 7. 구현 순서 (저위험 골격, 승인 후)

1. 1A/1B/2A/2B 4상수를 **섹션 단위 상수로 재분해** → 재조립 == 원본 바이트 동일성(sha256 761e9bda...) 검증.
2. `CORE`·`MODULE_STRUCT`·`MODULE_WORLD`·`MODULE_ROLES`·`MODULE_ITEMS`를 `String.join`으로 조립(공유 섹션 단일 상수 참조).
3. 4개 `callGmAiLarge`의 첫 인자만 `CORE+MODULE_x`로 교체. `GDAM_SYSTEM_PROMPT`(전체 join)는 폴백 결합본 용도로 잔존.
4. 계약 #4·#5 신규 구현(struct→items world_rules/timeline 요약, world→items 봉인 다이제스트) + 계약 #3 capstone 필터.
5. 필수 가드(§5) 이행: item content≠capstone lint, 폴백·재시도 동반 수정, lintGdam 커버리지 재검증.
6. 검증: bracecheck OK 0/0/0 → javac(75클래스) → 스탠드얼론 로직 테스트(바이트 동일성·lint 커버리지).

## 8. 남은 위험 (구현 시 주의)

- struct 모듈이 여전히 ≈67KB(전체 ~53%) — 스케일 기준(7.1KB)·constraints(8.1KB)를 스케일별 조건부 삽입으로 다시 쪼개면 2차 절감 여지(1차 범위 밖).
- items=최종 청크 전제에 solution/collapse 전달 안전성이 의존 → **청크 순서 변경 시 규약 붕괴**를 코드 주석으로 명시.
- 힌트관문판(30%)에서 world가 만든 clue_hintgate(capstone·requires_event_resolved)가 world→items 다이제스트에 반드시 반영 → `lintHintGate` 통과로 회귀 확인.

## 9. 구현 기록 (실제)

설계의 '물리적 44개 상수 재분해' 대신 **런타임 분할**을 택했다 — 원본 4상수(1A/1B/2A/2B)·`GDAM_SYSTEM_PROMPT`를 **한 글자도 안 건드려** 폴백 바이트 동일성을 구조적으로 보장하고, 분할은 순수 문자열 연산으로 수행. 설계 대비 규칙 손실 위험(텍스트블록 절단)이 원천 제거되고, '단일 소스·다중 참조'도 자동 충족(원본 하나를 분할).

- **커밋 `31c3f15`(1/2)**: `buildSystemModules(full)` — `## ` 경계 무손실 분할(`splitPromptSections`) + 헤더 키워드 배정(`MOD_ASSIGN`, 43섹션) + 스키마 최상위 필드 슬라이스(`MOD_SCHEMA_FIELD`, `promptSchemaSlice`) → `SYS_STRUCT/WORLD/ROLES/ITEMS`(= CORE_HEAD[머리말·출력규칙·언어수준] + 모듈 섹션 + 스키마 조각 + CORE_TAIL[최종자기검증]). 커버리지 자기검증 실패 시 **4모듈 전부 전체 프롬프트로 폴백**(현행 동작). 4개 `callGmAiLarge` + `chunkArray(sys 인자)` 를 SYS_x로 교체. 폴백 `generate()`는 전체 유지.
- **커밋 `869376d`(2/2)**: 계약 struct→items(itemCtx에 world_rules·timeline) · **신규 world→items `sealedClueDigest`** · **clueDigest capstone 필터** · itemsPrompt 해법-비노출 지침 · **가드 `lintItemVsCapstone`**(capstone 정답이 아이템 content에 20자+ 복제 시 경고, lintGdam 4d).

**검증**: bracecheck OK 0/0/0 · javac 75클래스 · 리플렉션 프로브(폴백 sha256 `bfadd79a…` 불변 · 모듈 폴백 안 됨 · SYS 크기·스팟체크) · 스탠드얼론 75/75(ModBuild 19 + ContractTest 6 신규 포함). **실플레이 생성 품질은 컴파일 전용 환경에서 미검증 — 실측 로그 필요(#112).**

### 남은 2차 최적화(1차 범위 밖)
- struct 모듈 ≈67KB가 여전히 최대 — 스케일 기준(7.1KB)·constraints(8.1KB)를 스케일별 조건부 삽입으로 재분할하면 추가 절감.
- 폴백 `generate()`는 여전히 전체 프롬프트(정상 경로만 최적화 — 폴백은 회귀 안전이 우선).
