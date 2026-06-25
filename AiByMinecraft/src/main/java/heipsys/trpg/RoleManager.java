package heipsys.trpg;

import com.google.gson.*;
import heipsys.trpg.model.PlayerData;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * .gdam roles 로드 → 플레이어 수에 따라 배역 배정 (STEP 4-1).
 * 핵심 배역 우선, 부족하면 NPC 대체, 5인 초과 시 주변인 배역.
 */
public class RoleManager {

    private final GameStateManager state;

    /** 배역 배정 결과 */
    public record RoleAssignment(String roleId, String roleName, String zone, List<String> initialInfo, boolean knowledgeAdvantage) {}

    /** 도중 참여 가능 최대 타임라인 단계 */
    private static final int MID_JOIN_TIMELINE_LIMIT = 3;

    public RoleManager(GameStateManager state) {
        this.state = state;
    }

    // ──────────────────────────────────────────────────────────────
    //  배역 배정
    // ──────────────────────────────────────────────────────────────

    /** 현재 플레이어 목록에 배역 일괄 배정. NPC 대체 목록도 반환. */
    public Map<UUID, RoleAssignment> assignRoles(List<Player> players) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return Collections.emptyMap();

        JsonArray rolesArr = gdam.getAsJsonArray("roles");

        // 핵심 배역 먼저 추출
        List<JsonObject> coreRoles  = new ArrayList<>();
        List<JsonObject> extraRoles = new ArrayList<>();
        for (JsonElement el : rolesArr) {
            JsonObject r = el.getAsJsonObject();
            if (r.has("is_core") && r.get("is_core").getAsBoolean()) coreRoles.add(r);
            else                                                       extraRoles.add(r);
        }

        // 배정 순서 결정
        List<JsonObject> ordered = new ArrayList<>(coreRoles);
        ordered.addAll(extraRoles);

        Map<UUID, RoleAssignment> result = new LinkedHashMap<>();
        List<Player> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        for (int i = 0; i < shuffled.size() && i < ordered.size(); i++) {
            Player p  = shuffled.get(i);
            JsonObject role = ordered.get(i);
            RoleAssignment asgn = toAssignment(role);
            result.put(p.getUniqueId(), asgn);

            PlayerData pd = state.getPlayer(p);
            if (pd != null) {
                pd.roleId = asgn.roleId();
                pd.zone   = asgn.zone();
                pd.roleAssigned = true;
            }
        }

        // 배정 못 받은 핵심 배역 → NPC 대체 알림만 (실제 NPC 생성은 TRPGGameManager)
        return result;
    }

    /** 도중 참여: 남은 배역 배정 또는 주변인 역할 */
    public Optional<RoleAssignment> assignLateJoin(Player player) {
        if (state.getTimelineStage() > MID_JOIN_TIMELINE_LIMIT) return Optional.empty();

        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return Optional.empty();

        JsonArray rolesArr = gdam.getAsJsonArray("roles");
        Set<String> assignedRoles = new HashSet<>();
        state.getAllPlayers().forEach(pd -> assignedRoles.add(pd.roleId));

        // 미배정 핵심 배역 우선
        for (JsonElement el : rolesArr) {
            JsonObject r = el.getAsJsonObject();
            String rid = r.get("role_id").getAsString();
            if (!assignedRoles.contains(rid)) {
                RoleAssignment asgn = toAssignment(r);
                PlayerData pd = state.getPlayer(player);
                if (pd != null) { pd.roleId = asgn.roleId(); pd.zone = asgn.zone(); pd.roleAssigned = true; }
                return Optional.of(asgn);
            }
        }

        // 없으면 주변인 배역 생성
        RoleAssignment peripheral = new RoleAssignment(
            "role_peripheral", "주변인", "zone_A",
            List.of("현재 상황에 갑자기 휘말린 인물이다."), false
        );
        PlayerData pd = state.getPlayer(player);
        if (pd != null) { pd.roleId = "role_peripheral"; pd.zone = "zone_A"; pd.roleAssigned = true; }
        return Optional.of(peripheral);
    }

    // ──────────────────────────────────────────────────────────────
    //  배역 정보 조회
    // ──────────────────────────────────────────────────────────────

    public String getRoleBriefing(String roleId, int corruptionLevel) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("roles")) return "배역 정보 없음.";

        for (JsonElement el : gdam.getAsJsonArray("roles")) {
            JsonObject r = el.getAsJsonObject();
            if (!r.has("role_id")) continue;
            if (r.get("role_id").getAsString().equals(roleId)) {
                StringBuilder sb = new StringBuilder();
                String name = r.has("name") ? r.get("name").getAsString() : roleId;
                String spawnLoc = r.has("spawn_location") ? r.get("spawn_location").getAsString() : "알 수 없음";
                sb.append("§e[배역: ").append(name).append("]\n");
                sb.append("§7시작 위치: §f").append(spawnLoc).append("\n");
                sb.append("§8(배역의 배경 지식은 GM이 프롤로그를 통해 자연스럽게 전달합니다.)");
                return sb.toString();
            }
        }
        return "배역 정보를 찾을 수 없습니다: " + roleId;
    }

    /** 늦은 참여자 배경 브리핑 (판도를 뒤집을 정보 포함, 강조 없이) */
    public String getLateJoinBriefing(String roleId) {
        return getRoleBriefing(roleId, state.getCorruption().level)
            + "\n§7(현재 상황에 자연스럽게 합류하게 됩니다.)";
    }

    // ──────────────────────────────────────────────────────────────
    //  내부 유틸
    // ──────────────────────────────────────────────────────────────

    private RoleAssignment toAssignment(JsonObject r) {
        String roleId   = r.has("role_id")   ? r.get("role_id").getAsString()   : "role_?";
        String roleName = r.has("name")      ? r.get("name").getAsString()      : "알 수 없는 배역";
        String zone     = r.has("zone")      ? r.get("zone").getAsString()      : "zone_A";
        boolean adv     = r.has("knowledge_advantage") && r.get("knowledge_advantage").getAsBoolean();

        List<String> info = new ArrayList<>();
        if (r.has("initial_info")) {
            r.getAsJsonArray("initial_info").forEach(i -> info.add(i.getAsString()));
        }
        return new RoleAssignment(roleId, roleName, zone, info, adv);
    }
}
