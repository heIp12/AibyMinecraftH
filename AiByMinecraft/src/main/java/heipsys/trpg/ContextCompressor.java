package heipsys.trpg;

import heipsys.trpg.model.EventLogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * AI 컨텍스트 압축 (STEP 6-2).
 * 이벤트 로그 30개 초과 시 앞 20개를 Haiku로 5줄 요약 → GM AI 컨텍스트에 주입.
 */
public class ContextCompressor {

    private final AiManager        ai;
    private final GameStateManager state;

    private static final int COMPRESS_THRESHOLD = 20;  // was 30
    private static final int RECENT_KEEP        = 7;   // was 10
    private static final int OLD_BATCH_SIZE     = 13;

    public ContextCompressor(AiManager ai, GameStateManager state) {
        this.ai    = ai;
        this.state = state;
    }

    /**
     * 로그가 임계치를 초과하면 비동기 압축 수행 후 GM 컨텍스트에 주입.
     * @return true = 압축 시작됨
     */
    public boolean compressIfNeeded() {
        if (state.getLogSize() <= COMPRESS_THRESHOLD) return false;

        List<EventLogEntry> liveLog = state.getLog();
        // snapshot 생성을 liveLog로 동기화 — async cleanup과 main-thread log() 간 race 방지
        final List<EventLogEntry> snapshot;
        synchronized (liveLog) {
            int cutoff = liveLog.size() - RECENT_KEEP;
            if (cutoff <= 0) return false;
            int batchSize = Math.min(cutoff, OLD_BATCH_SIZE);
            snapshot = new ArrayList<>(liveLog.subList(0, batchSize));
        }
        String rawLog = snapshot.stream().map(e -> e.toLogString(state::resolveDisplayName)).collect(Collectors.joining("\n"));

        String task = "아래 TRPG 이벤트 로그를 핵심만 5줄 이내로 요약해줘.\n"
            + "반드시 포함: 단서 발견, 타임라인 변화, 플레이어 상태 변화, 아이템 획득.\n"
            + "제외: 실패한 탐색, 반복 행동, 일상 대화.";

        ai.callAssistant(task, rawLog).thenAccept(summary -> {
            ai.compressGmContext(summary);
            // 압축된 항목만 제거 (snapshot 크기만큼 앞에서 제거, 추가된 항목은 보존)
            synchronized (liveLog) {
                int toRemove = Math.min(snapshot.size(), liveLog.size());
                liveLog.subList(0, toRemove).clear();
            }
        });

        return true;
    }

    /**
     * 일상 파트 종료 시 일상 파트 전체를 복선 요약으로 교체.
     */
    public CompletableFuture<Void> compressDailyPhase() {
        List<EventLogEntry> daily = state.getLog().stream()
            .filter(e -> "action".equals(e.type) || "system".equals(e.type))
            .toList();

        if (daily.isEmpty()) return CompletableFuture.completedFuture(null);

        String rawLog = daily.stream().map(e -> e.toLogString(state::resolveDisplayName)).collect(Collectors.joining("\n"));
        String task   = "아래는 TRPG 일상 파트의 대화/행동 로그입니다.\n"
            + "나중에 '그게 그거였구나' 하는 순간을 만들 수 있도록\n"
            + "핵심 복선과 중요 정보만 3줄로 압축 요약해줘.";

        return ai.callAssistant(task, rawLog).thenAccept(summary -> {
            // ★일상 요약은 injectGmSystem으로★(compressGmContext는 gmContext<=20이면 no-op이라 일상은 대개 요약을 만들고
            //   버렸다 — 요약 AI 비용만 쓰고 폐기). injectGmSystem은 크기 무관하게 다음 GM 호출(공포 첫 턴)에 요약을 전달한다.
            if (summary != null && !summary.isBlank()) ai.injectGmSystem("[일상 파트 요약] " + summary);
        });
    }
}
