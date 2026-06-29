# ABILITY_FRAMEWORK.md — 능력(특성) 프레임워크 확장 설계

목표: GM(AI)이 괴담동아리·괴담호텔식 **다양한 능력**을 자유롭게 만들고 조절하게,
"Java가 기계 효과를 정의 / AI가 이름·등급·수치·발현을 변주"하는 **기존 패턴 위에** 얹는다.
(작성 2026-06-27. 설계만 — 코드 미변경. 승인 후 단계 적용용.)

---

## 0. 핵심 결론 — 새 시스템이 아니라 "확장"

현재 `SystemTraitRegistry` + `TraitData`가 **이미 원하는 패턴 그 자체**다:
- `effect_type`(프리미티브 키) + `effect_params`(AI가 조절하는 수치) + AI가 짓는 이름/등급/설명/쿨다운.
- `buildAiCatalog()`가 AI에게 "메뉴 + 등급 밸런스 규칙"을 준다.

따라서 할 일은 **재설계가 아니라 4개 축의 확장**:
| 축 | 현재 | 부족분(소설 예시가 요구) |
|---|---|---|
| ① 발동 방식 | active(버튼)/passive 2종 | **자동/수동/상태조건** 3분류 명시 + 구체 상태조건 |
| ② 레벨·강화 | `replacesId`로 보상 교체만 | **LV.1→4차 단계 강화 + 기여도 게이팅** |
| ③ 효과 프리미티브 | 14종 | **이동·원격감지·예지·확정성공·사교·지배·운명 등 신규** |
| ④ 개인화 발현 | AI가 flavor 작성(암묵) | **캐릭터 유산·성향별 발현**을 명시 필드로 |

---

## 1. 현재 구조 (3층) — 변경 없이 그대로 쓰는 토대

```
TraitData (model/TraitData.java)
  effectType:String  effectParams:Map<String,Int>  grade  cooldownTurns  replacesId
  + 영구 스탯보정(str/cha/luk/spr/hp_max/san_max_add)

SystemTraitRegistry.Effect (enum) — 프리미티브 14종
  능동: AI_QUERY INSTANT_CLEAR REVIVE_ALLY LUCK_ROLL SHOW_PROGRESS
        CHOICE_ACTION GM_DIRECTIVE AREA_SCAN SACRIFICE LINK_ALLY
  패시브: SCENARIO_INSIGHT PASSIVE_GM PASSIVE_TRIGGER PROTECT
  buildAiCatalog() → AI 생성 프롬프트(메뉴+등급표) / applyDefaults() → 수치 검증·clamp

런타임 연결 (TRPGGameManager)
  능동: activateTrait switch (L2429) → activateXxx(player,pd,td)
  패시브: GM 프롬프트 주입 switch (L4883) — passive_gm/passive_trigger/protect 블록
  scenario_insight: 배역 배정 시 적용(L2841) + 본인 구조 공개(L1284)
```

**확장 규칙(불변):** 새 능력 = ①`Effect` enum 1줄 추가 → ②`applyDefaults` 기본값 → ③능동이면 dispatch case+`activateX`/패시브면 주입 case → ④`buildAiCatalog`에 메뉴·등급 1줄. 그 외 파일 영향 없음.

---

## 2. 축① 발동 방식 — 이미 3종이 있다(명시만)

호텔 "조건: 자동/수동/상태조건"은 **현재 개념에 그대로 대응**한다. 새 메커니즘 불필요, **카탈로그 문구로 명시**만:
| 소설 조건 | 우리 구현 | 비고 |
|---|---|---|
| 수동 | `active=true` (버튼) | 기존 |
| 자동 | `passive_gm` (매 턴 상시 고려) | 기존 |
| 상태조건 | `passive_trigger` (조건 충족 시 자동) | 기존, 단 조건이 free-text |

**소폭 개선(선택):** `TraitData.triggerWhen:String` 1필드 추가 → 상태조건을 구조적으로 기술
(`"hp<30%"`, `"alone"`, `"night"`, `"ally_near"`, `"in_dark"`). 코드가 체크 가능한 건 코드가, 아니면 GM이 판정.
→ Phase A에선 free-text 유지로 충분(저위험), 필요 시 후속.

---

## 3. 축② 레벨·강화 (LV.1→4차, 기여도 게이팅)

소설의 "1차/2차/강력한강화/3차/4차, 기여도 필요"를 **기존 보상 흐름 재활용**으로 구현:

**신규 필드 (TraitData):** `int level=1; int maxLevel=1;`
**신규 카운터 (PlayerData):** `int contribution=0;` (기여도)
- **적립:** 스테이지 평가 등급 → 점수(S+5 A+4 B+3 C+2 D+1 F+0). 이미 매 스테이지 평가가 `grades` 맵 생성(평가 시스템 완비) → 거기서 누적.
**강화 = 기존 `replacesId` 흐름 그대로:** 클리어 보상 단계에서 "같은 effectType·level+1·effectParams 상향" 특성을 보상 후보로 제시(replacesId=현 특성). 기여도 임계 미달이면 후보에서 제외.
- AI가 각 tier의 이름/설명/effect를 변주하고, **수치는 자동 스케일**(예 scope 1→3, uses 1→3, info 1→3, power 1→3).

**워크드 예시 — 천리안(REMOTE_SENSE):**
```
LV.1 (B) "옆방 엿보기"     remote_sense(range=1,uses=1)   기여도 0
LV.2 (B) "층 투시"         remote_sense(range=2,uses=1)   기여도 8
강력한강화(A) "건물 투시"   remote_sense(range=3,uses=2)   기여도 16
LV.3 (A) "원거리 감청"      + 대화 내용까지(info)           기여도 24
4차 (S) "전지적 시점"       remote_sense(range=3,uses=3,info=3) 기여도 35
```
→ 코드는 "level/maxLevel 추적 + 기여도 적립 + 보상시 level+1 변형 생성"만, 효과는 기존 프리미티브 수치 상향이라 **신규 런타임 거의 없음**.

---

## 4. 축③ 신규 효과 프리미티브 (소설 예시 → 프리미티브)

기존 14종으로 안 되는 것만 신설. 각 enum 시그니처 `(key, active, whatItDoes, paramHint)`:

### 능동(activateX 메서드 필요)
| 키 | 효과 | 파라미터 | 등급 | 커버 예시 |
|---|---|---|---|---|
| `guaranteed` | 다음 행동 1회 **확정 성공**(주사위 무시) | `uses`(1~2), `scope`(1=소행동,2=중,3=중대) | A~S | 실패하지않는고등학생 |
| `mobility` | **이동·도주·돌파**: 다음 이동 즉시·추격 회피·gated_zone 우회 보정 | `power`(1~3), `uses`(1~3) | B~A | 빠른걸음·파쿠르·개인형탈것 |
| `remote_sense` | **타 구역 원격 감지**(현 구역 한정 area_scan과 구분) | `range`(1=인접,2=층,3=전역), `info`(0~3), `uses` | B~S | 독순술·천리안 |
| `foresight` | **다음 행동/사건의 결과 미리보기**(분기 예측) | `depth`(1=직후,2=다음사건,3=분기다수), `uses` | A~S | 예지·인생설계 |
| `social` | **NPC 호감·설득·강제 접촉**(NPC 반응 보정) | `power`(1~3), `uses` | B~A | 친화·소통 |
| `dominate` | **NPC·개체 일시 제어**(명령 1회 강제) — 균형 민감 | `power`(1~2), `uses`(1) | A~S | 지배 |
| `fate` | **결과 리롤/운명 개입**(판정 1회 뒤집기) — 최상위 희소 | `uses`(1) | S | 운명 |
| `resource` | **아이템·자원 확보/소환** | `tier`(1~3), `uses` | B~A | 부귀·개인형탈것(소환측) |
| `group_rewind` | **파티 회귀**(직전 분기로 공유 되감기) — 최상위 희소 | `uses`(1) | S | 동반회귀 |

### 패시브(프롬프트 주입만 — 저비용)
| 키 | 효과 | 파라미터 | 등급 | 커버 |
|---|---|---|---|---|
| (PROTECT 확장) | effect 텍스트로 **상태이상·변화 저항**까지 포괄 | `power` | C~A | 불변·오줌참기·용기 |
| `passive_gm` | 현대지식 우위·행운가호 등 상시(기존 재사용) | — | C~S | 현대인천재론·행운의여신·성실 |
| `passive_trigger` | 정의 응징·위기 자동대응(기존 재사용) | intensity,trigger_freq | C~A | 정의·용기 |

> 비밀(`reveal_hidden`)·지혜는 기존 `ai_query(info=3)`/`scenario_insight`로 흡수 가능(신설 선택).

---

## 5. 전체 커버리지 표 (예시 30종 → 매핑) — 누락 없음 확인

**괴담동아리**
| 예시 | 매핑 |
|---|---|
| 행운의여신 | passive_gm("행운 상시") 또는 luck_roll(자동화) |
| 독순술 | **remote_sense(range=1~2, info=3)** |
| 인생설계 | **foresight(depth=2~3)** |
| 클리셰발현 | gm_directive("호러 클리셰 강제 발동") |
| 실패하지않는고등학생 | **guaranteed(uses=1~2)** |
| 현대인천재론 | passive_gm("현대 지식·상식 우위") |
| 빠른걸음 | **mobility(power=2)** |
| 동반회귀 | **group_rewind(S)** |
| 오줌참기 | protect(power=1, effect="생리·상태 저항") — 개그 D~C |
| 개인형탈것 LV.3 | **mobility + resource** + 레벨(축②) |
| 파쿠르 | **mobility(power=2, effect="지형 돌파·도주")** |

**괴담호텔 가호(자동/수동/상태조건·LV 1~4차 = 축①·②로 표현)**
| 가호 | 매핑 |
|---|---|
| 지혜 | scenario_insight / ai_query(info) |
| 용기 | protect(정신) + passive_trigger(공포저항) |
| 친화 | **social** |
| 부귀 | **resource** |
| 정의 | passive_trigger / gm_directive |
| 행운 | luck_roll / passive_gm |
| 소통 | **social** / link_ally |
| 비밀 | ai_query(info=3) 또는 **reveal_hidden**(선택) |
| 성실 | passive_gm + **기여도 적립 가속**(축②) |
| 불변 | protect(effect="상태·변화 저항") |
| 지배 | **dominate** |
| 천리안 | **remote_sense** |
| 예지 | **foresight** |
| 운명 | **fate** |

→ 신규 프리미티브 **8종**(guaranteed, mobility, remote_sense, foresight, social, dominate, fate, resource) + group_rewind = 9종으로 **전 예시 커버**. 나머지는 기존 재사용.

---

## 6. 코드 변경 범위·난이도

| 항목 | 파일 | 난이도 | 위험 |
|---|---|---|---|
| 축① trigger 문구 명시 | SystemTraitRegistry(catalog) | 낮음 | 낮음(프롬프트) |
| 축① triggerWhen 필드(선택) | TraitData + 주입부 | 낮음 | 낮음 |
| 축② level/maxLevel | TraitData | 낮음 | 낮음 |
| 축② contribution 적립 | PlayerData + 평가완료 콜백 | 중간 | 중간(평가흐름 연계) |
| 축② 강화 보상 생성 | 보상 생성부(replacesId 재활용) | 중간 | 중간 |
| 축③ 패시브 신규(social-passive 등) | enum + 주입 case + catalog | 낮음 | 낮음 |
| 축③ 능동 신규(mobility/remote_sense/foresight/guaranteed/social) | enum + dispatch case + activateX + applyDefaults + catalog | 중간(각) | 중간 |
| 축③ S급(dominate/fate/group_rewind) | 上 + 밸런스·상태조작 | 높음 | 높음(런타임 상태 변경) |
| 축④ origin 필드(발현) | TraitData + 생성/주입 프롬프트 | 낮음 | 낮음 |

---

## 7. 권장 적용 순서 (비용·위험 최소 우선)

- **Phase A — 프롬프트·저위험(런타임 거의 무변):** 축① 3분류 명시 + 축③ 패시브 신규/PROTECT 확장 + 카탈로그에 소설 예시 매핑 추가 + 축④ origin 필드.
  → 코드 위험 거의 0으로 예시 **~60% 커버**(자동/상시/저항/지식/행운/클리셰 계열).
- **Phase B — 능동 신규 5종:** guaranteed, mobility, remote_sense, foresight, social. 각 `activateX` 작성. gated_zones/zone/타임라인과 연계.
- **Phase C — 레벨·강화(축②):** contribution 적립 + 강화 보상. 기존 보상 흐름 재활용.
- **Phase D — S급 특수 3종:** dominate, fate, group_rewind. 밸런스·상태조작 신중히 마지막.

각 Phase는 독립 커밋(컴파일 불가 환경 — 정적검증 후 사용자 로컬 빌드).
```
```
