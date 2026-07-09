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

    /** ★행동마다 미리 굴려둔 능력치별 판정값 노트★ 제공자(TRPGGameManager 등록) — turnInput 머리에 얹어 GM이 판정 결과를 알고 서술을 이어 쓰게(#254). */
    private java.util.function.Function<Player, String> preRollProvider;

    public record GmResponse(Player player, String rawText) {}

    public TurnManager(GameStateManager state, AiManager ai) {
        this.state = state;
        this.ai    = ai;
    }

    public void setResponseHandler(Consumer<GmResponse> handler) {
        this.responseHandler = handler;
    }

    public void setPreRollProvider(java.util.function.Function<Player, String> provider) {
        this.preRollProvider = provider;
    }

    // ──────────────────────────────────────────────────────────────
    //  행동 입력 처리
    // ──────────────────────────────────────────────────────────────

    /**
     * 플레이어 채팅 행동 처리.
     * @return false이면 이 플레이어는 현재 행동 처리 중 (중복 입력 차단)
     */
    public boolean handleAction(Player player, String message, String gmSystemPrompt) {
        return handleAction(player, message, gmSystemPrompt, "");
    }

    /**
     * 행동 처리 + 이번 턴 한정 GM 지시(turnCtx).
     * turnCtx는 ★유저 메시지 쪽★에 얹는다(시스템 프롬프트를 변형하지 않음) →
     * gmSystemPrompt가 항상 동일 문자열로 유지되어 프롬프트 캐시가 적중한다(능력 처리 시 입력비용 ~90%↓).
     * 로그(narrativeLog·state.log)에는 순수 행동(message)만 남겨 지시문 오염을 막는다.
     */
    public boolean handleAction(Player player, String message, String gmSystemPrompt, String turnCtx) {
        PlayerData pd = state.getPlayer(player);
        if (pd == null || pd.isDead) return false;

        // 이미 ACTING 상태면 추가 입력 차단
        if (pd.turnState == TurnState.ACTING) return false;

        pd.turnState = TurnState.ACTING;
        state.nextTurn();
        state.log("action", pd.name, message);

        synchronized (pd.narrativeLog) {
            pd.narrativeLog.add("[행동▷] " + message);
            if (pd.narrativeLog.size() > PlayerData.NARRATIVE_LOG_MAX)
                pd.narrativeLog.remove(0);
        }

        String turnInput = state.buildTurnInput(player, message);
        if (turnCtx != null && !turnCtx.isEmpty()) turnInput = turnCtx + "\n\n" + turnInput;
        // ★미리 굴려둔 능력치별 판정값★을 turnInput 머리에 얹는다 — GM이 판정 순간에 이 값으로 성패를 정해 서술을 이어 쓰게(#254 인라인 주사위).
        if (preRollProvider != null) {
            String pr = preRollProvider.apply(player);
            if (pr != null && !pr.isEmpty()) turnInput = pr + "\n\n" + turnInput;
        }

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
