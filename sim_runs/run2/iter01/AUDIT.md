# run2/iter01 사후 검증 — 메아리 간호사 #ECHO-9F2K

엔진 시뮬(변칙·플레이어식)을 1회 끝까지 구동하고 실제 코드 동작과 대조. 신규 패치 집중.

## 신규 기능 검증 결과

| # | 기능 | 코드 근거 | 결과 |
|---|---|---|---|
| 1 | 계정명 차단 | `PlayerData.gmDisplayName`, `GameStateManager.resolveDisplayName/toTurnLine/toShortLine`, `commDisplayName` | ✅ 서술·NPC·보상 전부 캐릭터명. 계정명 0건 |
| 2 | zone 필터(타 장면 행동 미혼입) | `buildEntityLog(limit,zone)`, `isPerceivableEvent`, "최근(같은 장면)" | ✅ 수렴 전 각 시점 독립. 단 **F1 의존성** 있음(아래) |
| 3 | 합류 필수 서술 | `updatePlayerZone` `[합류]` 주입 + GM 규칙 | ◐ 동작하나 **F1 의존**(ZONE_UPDATE 발행 전제) |
| 4 | 조우판정(같은 zone 시작) | 프롤로그 same-zone 동료 주입 | ⏭ 미발생(이번 판 전원 다른 zone) → iter02에서 검증 |
| 5 | 문장 줄바꿈 20자/2줄 | `NarrativeDelivery` MAX_CHAT_CHARS=20, splitSentences | ✅(구조). 실측은 인게임 필요 |
| 6 | 중복 입력 디바운스 | `handleGameChat` 2.5s exact | ✅ 23:24 동일 2연타 → 1회만 처리 |
| 7 | @전체 = 아는 번호 전원 | `broadcastToKnownContacts` | ✅ 히카루(known=오민재)→1명. known0(정우식)→"아는 번호 없음" |
| 8 | @ 근처(같은 zone+spot) | `proximityBroadcast` | ✅ 옥상 혼자 → "들을 사람 없음" |
| 9 | 띄어쓰기 이름 @통신 | `matchCommToken` 최장 일치 | ✅ "@히카루 사토" 토큰 정상 인식 |
| 10 | 이름만으론 통화 실패(번호 필요) | `handleDirectComm` contactKnown | ✅ 번호 모르는 이름 통화 실패 |
| 11 | 통신유인(comms_dangerous) | `noteCommUsedIfDangerous` 주입 | ✅ 무전·통화 직후 1턴 내 접근 |
| 12 | 특성 보상 상한·코스트·등급검증 | `maxRewardGrade(2,..)`, `enforcePowerBudget`, `normalizeGrade` | ✅ 전부 ≤A(스2). 코스트+스텟 ≤ 등급예산 |
| 13 | AI 품질 중품질 기본 | `AiManager.Quality.MEDIUM` | ✅(코드 기본값) |

## 발견(Findings)

### F1 (높음·기존 보류 항목 재확인) — 합류 서술·zone 수렴이 `<ZONE_UPDATE>` 발행에 의존
2·3번은 **GM이 플레이어 이동 시 `<ZONE_UPDATE>` 태그를 실제로 내야** 성립한다. GM이
서술로만 "계단을 오른다"하고 태그를 안 내면 → 추적 zone이 스폰값에 고정 → ⓐ `[합류]`
주입 안 됨, ⓑ zone 필터가 같은 장소로 인식 못 해 협동·상호 인지 누락. (이전에 사용자가
보류한 co-location 동기화 이슈와 동일 뿌리.) 이번 시뮬은 ZONE_UPDATE가 난다는 가정하에
정상 동작했으나, **실주행 위험은 그대로**. → 보류 해제 시: GM 프롬프트에 "이동 서술 시
반드시 ZONE_UPDATE 동봉" 강제 + area 기준 협동 완화 권장.

### F2 (실제 버그) — 번호 직접 다이얼(@1234)이 '번호 인지' 검사를 우회
`handleDirectComm`:
```java
boolean contactKnown = dialedByNumber
    || senderPd.knownContacts.contains(targetPd.uuid)
    || targetPd.knownContacts.contains(senderPd.uuid);
```
`dialedByNumber`가 true면 무조건 통과 → **학습하지 않은(모르는) 번호를 찍어도 연결**된다.
시뮬 23:23에서 정우식이 알 리 없는 4471(오민재)을 찍는 변칙 입력이 이를 노출.
사용자 요구("전화번호를 알아야 통화 가능")와 충돌. → 다이얼도 knownContacts/everKnown에
있을 때만 연결되도록 수정 필요. **이번 iter 직후 수정 적용.**

### F3 (경미) — 근거리 발화(@ 근처)·실내 외침은 comms_dangerous 주입 대상 아님
`noteCommUsedIfDangerous`는 기기 통신(@전화/@전체)에만 발동. proximity 발화·행동 외침은
GM 서술로만 위험 반영(이번 시뮬에선 GM이 잘 처리). 청각형 괴담에선 '모든 소리'가 위험인데
주입은 기기에만 걸려 일관성 약간 부족. → 선택: world_rules 'comms_dangerous+청각'일 때
proximity/외침도 약한 주입. (낮은 우선순위)

## 변칙 진행 스트레스(플레이어식) — 처리 양호
- 오타/한글 깨짐(ㅁㄴㅇㄹ, ㄴㄴ), 도배(2연타), 패닉 무전, 자기파괴 시도, 힌트 무시(불끄기·도망
  미스리드), 번호 모름 다이얼, @ 남발 → 전부 크래시 없이 서술·시스템 메시지로 흡수.
- 자기파괴("뛰어내릴까") → 강제 사망 아님, 서술 만류(P14 계열) 정상.

## 결론
신규 패치 대부분 정상. **즉시 조치 1건(F2)**, 보류 재확인 1건(F1), 경미 1건(F3).
