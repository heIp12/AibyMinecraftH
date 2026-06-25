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

    private static final int COMPRESS_THRESHOLD = 30;
    private static final int RECENT_KEEP        = 10;
    private static final int OLD_BATCH_SIZE     = 20;

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
        int cutoff = liveLog.size() - RECENT_KEEP;
        if (cutoff <= 0) return false;

        // 스냅샷: 비동기 콜백이 끝나기 전에 live list가 변해도 안전
        int batchSize = Math.min(cutoff, OLD_BATCH_SIZE);
        List<EventLogEntry> snapshot = new ArrayList<>(liveLog.subList(0, batchSize));
        String rawLog = snapshot.stream().map(EventLogEntry::toLogString).collect(Collectors.joining("\n"));

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

        String rawLog = daily.stream().map(EventLogEntry::toLogString).collect(Collectors.joining("\n"));
        String task   = "아래는 TRPG 일상 파트의 대화/행동 로그입니다.\n"
            + "나중에 '그게 그거였구나' 하는 순간을 만들 수 있도록\n"
            + "핵심 복선과 중요 정보만 3줄로 압축 요약해줘.";

        return ai.callAssistant(task, rawLog).thenAccept(summary -> {
            ai.compressGmContext("[일상 파트 요약] " + summary);
        });
    }
}
