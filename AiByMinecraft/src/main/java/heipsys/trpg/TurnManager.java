package heipsys.trpg;

import heipsys.trpg.model.PlayerData;
import heipsys.trpg.model.PlayerData.TurnState;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 턴 관리 (STEP 4-2).
 *
 * 시간 분할 구조:
 *   IDLE    — 입력 가능
 *   ACTING  — AI 응답 대기 중 (다른 IDLE 플레이어는 동시 입력 가능)
 *   WAITING — 미사용 (필요 시 확장)
 */
public class TurnManager {

    private final GameStateManager state;
    private final AiManager        ai;

    // player UUID → 진행 중인 Future (취소 가능)
    private final Map<UUID, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /** GM AI 응답 콜백 (TRPGGameManager에서 등록) */
    private Consumer<GmResponse> responseHandler;

    public record GmResponse(Player player, String rawText) {}

    public TurnManager(GameStateManager state, AiManager ai) {
        this.state = state;
        this.ai    = ai;
    }

    public void setResponseHandler(Consumer<GmResponse> handler) {
        this.responseHandler = handler;
    }

    // ──────────────────────────────────────────────────────────────
    //  행동 입력 처리
    // ──────────────────────────────────────────────────────────────

    /**
     * 플레이어 채팅 행동 처리.
     * @return false이면 이 플레이어는 현재 행동 처리 중 (중복 입력 차단)
     */
    public boolean handleAction(Player player, String message, String gmSystemPrompt) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.isDead) return false;

        // 이미 ACTING 상태면 추가 입력 차단
        if (pd.turnState == TurnState.ACTING) return false;

        pd.turnState = TurnState.ACTING;
        state.nextTurn();
        state.log("action", pd.name, message);

        String turnInput = state.buildTurnInput(player, message);

        CompletableFuture<Void> future = ai.callGmAi(gmSystemPrompt, turnInput)
            .thenAccept(response -> {
                pd.turnState = TurnState.IDLE;
                pending.remove(player.getUniqueId());
                if (responseHandler != null) {
                    responseHandler.accept(new GmResponse(player, response));
                }
            })
            .exceptionally(ex -> {
                pd.turnState = TurnState.IDLE;
                pending.remove(player.getUniqueId());
                return null;
            });

        pending.put(player.getUniqueId(), future);
        return true;
    }

    // ──────────────────────────────────────────────────────────────
    //  상태 조회
    // ──────────────────────────────────────────────────────────────

    public boolean isActing(Player player) {
        PlayerData pd = state.getPlayer(player);
        return pd != null && pd.turnState == TurnState.ACTING;
    }

    public boolean isIdle(Player player) {
        PlayerData pd = state.getPlayer(player);
        return pd != null && pd.turnState == TurnState.IDLE;
    }

    /** 모든 플레이어가 IDLE 상태인지 확인 */
    public boolean allIdle() {
        return state.getAllPlayers().stream()
            .filter(p -> !p.isDead)
            .allMatch(p -> p.turnState == TurnState.IDLE);
    }

    /** 세션 종료 시 모든 대기 Future 취소 */
    public void cancelAll() {
        pending.values().forEach(f -> f.cancel(true));
        pending.clear();
        state.getAllPlayers().forEach(pd -> pd.turnState = TurnState.IDLE);
    }
}
