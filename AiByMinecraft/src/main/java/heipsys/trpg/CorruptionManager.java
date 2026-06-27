package heipsys.trpg;

import com.google.gson.JsonObject;
import heipsys.trpg.model.PlayerData;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 오염 시스템 (STEP 6-3).
 * 메모리에만 존재 — .gdam 파일은 항상 원본 유지.
 * 재도전 시 오염도 상승, 세션 완전 종료 시 초기화.
 */
public class CorruptionManager {

    private final GameStateManager state;

    public CorruptionManager(GameStateManager state) {
        this.state = state;
    }

    // ──────────────────────────────────────────────────────────────
    //  오염도 조회
    // ──────────────────────────────────────────────────────────────

    public int getLevel()    { return state.getCorruption().level; }
    public int getAttempts() { return state.getCorruption().attempts; }

    // ──────────────────────────────────────────────────────────────
    //  클리어 후 보상 특성 등급 결정
    // ──────────────────────────────────────────────────────────────

    /** 클리어 등급에 오염도 보정 적용 후 최종 보상 등급 반환 */
    public String getRewardGrade(String clearGrade) {
        int boost = state.getCorruption().level;
        String[] grades = {"F","D","C","B","A","S"};
        int idx = gradeIdx(clearGrade) + boost;
        idx = Math.min(idx, grades.length - 1);
        return grades[idx];
    }

    /** 최고 오염 단계 도달 시 특수 특성 보상 여부 (CODE-17: 단계 수 규모 가변) */
    public boolean isSpecialReward() { return state.getCorruption().level >= state.getMaxCorruptionLevel(); }

    // ──────────────────────────────────────────────────────────────
    //  GM AI 컨텍스트 주입
    // ──────────────────────────────────────────────────────────────

    /** GM AI 시스템 프롬프트에 포함할 오염 지시사항 */
    public String buildCorruptionContext(JsonObject gdam) {
        int level = getLevel();
        if (level == 0) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n## 현재 오염 상태 (GM 참고)\n");
        sb.append("오염 단계: ").append(level).append(" (").append(getAttempts()).append("회차)\n");

        switch (level) {
            case 1 -> sb.append("단서 일부를 미묘하게 왜곡. 괴담이 플레이어를 언급하기 시작.\n");
            case 2 -> {
                sb.append("파훼 조건이 추가되었음. 타임라인이 단축됨.\n");
                // .gdam에서 orotect_path 추가 등은 GM AI가 서술로 처리
            }
            case 3 -> sb.append("플레이어의 특성을 역이용하는 방향으로 괴담이 행동.\n");
            case 4 -> sb.append("완전 변질 — 괴담이 독립적 의지로 플레이어를 표적. 클리어 시 전용 특수 특성 지급.\n");
        }

        // 독립 괴담 AI 메모리 누적
        if (!state.getCorruption().entityMemory.isEmpty()) {
            sb.append("괴담이 기억하는 플레이어 행동 패턴:\n");
            state.getCorruption().entityMemory.forEach(m -> sb.append("  - ").append(m).append("\n"));
        }
        return sb.toString();
    }

    /** 괴담 AI 응답에서 학습한 행동 패턴을 메모리에 추가 */
    public void addEntityMemory(String memoryLine) {
        var em = state.getCorruption().entityMemory;
        em.add(memoryLine);
        if (em.size() > 20) em.remove(0); // 최근 20개만 유지
    }

    // ──────────────────────────────────────────────────────────────
    //  플레이어별 행동·말투 학습 (정체 차용/흉내용)
    // ──────────────────────────────────────────────────────────────

    /** 괴담이 특정 플레이어의 말/행동을 관찰해 학습 */
    public void learnPlayerBehavior(String playerName, String line) {
        if (playerName == null || line == null || line.isBlank()) return;
        var profiles = state.getCorruption().playerProfiles;
        var list = profiles.computeIfAbsent(playerName, k -> new java.util.ArrayList<>());
        String trimmed = line.trim();
        if (trimmed.length() > 120) trimmed = trimmed.substring(0, 120);
        list.add(trimmed);
        if (list.size() > 10) list.remove(0); // 최근 10개만 유지
    }

    /** 괴담이 학습한 특정 플레이어의 행동 패턴 */
    public List<String> getPlayerProfile(String playerName) {
        return state.getCorruption().playerProfiles.getOrDefault(playerName, Collections.emptyList());
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    private int gradeIdx(String grade) {
        return switch (grade) {
            case "S" -> 5;
            case "A" -> 4;
            case "B" -> 3;
            case "C" -> 2;
            case "D" -> 1;
            default  -> 0;
        };
    }
}
