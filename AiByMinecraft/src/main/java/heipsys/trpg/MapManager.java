package heipsys.trpg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import heipsys.trpg.model.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.*;
import java.util.List;

/**
 * 현장 약도(지도 아이템) 관리.
 *
 * .gdam zones[].connections 그래프를 128x128 filled_map에 그린다.
 * - 한글 방 이름: 번들 픽셀 폰트(neodgm, OFL)를 AWT BufferedImage에 렌더 → MapCanvas.drawImage.
 *   (마인크래프트 기본 MapFont는 ASCII 전용이라 한글 불가 → AWT 우회)
 * - 현재 위치: 깃발 핀.
 * - 공개 범위(viewer별): hasFullMap=true면 전체, 아니면 visitedZones만("가 본 곳만 그릴 수 있다").
 *
 * 두 가지 모드:
 * - 평면(flat): zones를 2열 그리드로. area가 1개 이하일 때.
 * - 계층(hierarchical): zone에 area(층/구역)가 2개 이상이면 상단=대분류(구역 전체, 현재 구역 강조),
 *   하단=소분류(현재 구역의 방들)로 한 장에 2단 배치. (칸 부족 대응)
 *
 * 입수 경로:
 *   /trpg map     — 직접 그린 약도(방문한 zone만)
 *   <MAP_GRANT>   — 스토리에서 전체 지도를 입수(전체 zone 공개)
 */
public class MapManager {

    private static final int SIZE = 128;

    // 약도 색상 (MC 지도 팔레트로 근사 매칭됨)
    private static final Color C_BG     = new Color(0xE8, 0xDC, 0xB5); // 양피지 바탕
    private static final Color C_EDGE   = new Color(0x6B, 0x4F, 0x2A); // 통로(연결선)
    private static final Color C_BOX    = new Color(0xF6, 0xEF, 0xD2); // 방 박스
    private static final Color C_BOX2   = new Color(0xE6, 0xC9, 0x7A); // 현재 위치 박스(강조)
    private static final Color C_BORDER = new Color(0x3A, 0x2A, 0x12); // 방 테두리
    private static final Color C_TEXT   = new Color(0x20, 0x18, 0x08); // 방 이름
    private static final Color C_FLAG   = new Color(0xCC, 0x22, 0x22); // 현위치 깃발
    private static final Color C_POLE   = new Color(0x33, 0x26, 0x12); // 깃대
    private static final Color C_DIV    = new Color(0x9A, 0x82, 0x55); // 구분선

    private final Plugin           plugin;
    private final GameStateManager state;
    private final NamespacedKey    mapKey;
    private Font                   font; // neodgm 13px (없으면 논리 폰트 폴백)

    // 현재 시나리오 그래프 (startDailyPhase에서 loadScenario로 갱신 — 메인 스레드)
    private final Map<String, String>      zoneNames = new LinkedHashMap<>(); // id → 표시 이름
    private final Map<String, String>      zoneArea  = new LinkedHashMap<>(); // id → 구역(area) 이름
    private final Map<String, Set<String>> adj       = new HashMap<>();       // id → 인접 id
    private List<String>                   zoneOrder = new ArrayList<>();

    // 계층 모드 데이터
    private boolean                        hierarchical = false;
    private final List<String>             areaOrder = new ArrayList<>();
    private final Map<String, Set<String>> areaAdj   = new HashMap<>();           // area → 인접 area
    private final Map<String, int[]>       overviewLayout = new HashMap<>();      // area → {px,py}
    private final Map<String, Map<String, int[]>> detailLayouts = new HashMap<>(); // area → (zone → {px,py})
    // 평면 모드 데이터
    private final Map<String, int[]>       flatLayout = new HashMap<>();           // zone → {px,py}

    private MapView view; // 시나리오 공유 MapView (최초 약도 요청 시 지연 생성)
    private final Map<UUID, String> lastSig = new HashMap<>(); // viewer별 마지막 렌더 시그니처

    public MapManager(Plugin plugin, GameStateManager state) {
        System.setProperty("java.awt.headless", "true");
        this.plugin = plugin;
        this.state  = state;
        this.mapKey = new NamespacedKey(plugin, "trpg_map");
        loadFont();
    }

    private void loadFont() {
        try (InputStream in = plugin.getResource("fonts/neodgm.ttf")) {
            if (in != null) font = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(13f);
        } catch (Exception e) {
            plugin.getLogger().warning("[map] 한글 폰트 로드 실패(약도 글자가 깨질 수 있음): " + e.getMessage());
        }
        if (font == null) font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    // ──────────────────────────────────────────────────────────────
    //  시나리오 로드 / 정리
    // ──────────────────────────────────────────────────────────────

    /** 새 .gdam의 zones+connections(+area)를 읽어 그래프·배치를 갱신. (메인 스레드에서 호출) */
    public void loadScenario(JsonObject gdam) {
        zoneNames.clear(); zoneArea.clear(); adj.clear(); zoneOrder = new ArrayList<>();
        areaOrder.clear(); areaAdj.clear(); overviewLayout.clear(); detailLayouts.clear(); flatLayout.clear();
        hierarchical = false;
        lastSig.clear(); // 보유 중인 약도를 새 그래프로 강제 재렌더
        if (gdam == null || !gdam.has("zones")) return;

        for (JsonElement el : gdam.getAsJsonArray("zones")) {
            if (!el.isJsonObject()) continue;
            JsonObject z = el.getAsJsonObject();
            String id = z.has("zone_id") ? z.get("zone_id").getAsString() : "";
            if (id.isEmpty()) continue;
            String name = z.has("name") && !z.get("name").getAsString().isBlank()
                ? z.get("name").getAsString() : id;
            zoneNames.put(id, name);
            zoneOrder.add(id);
            adj.computeIfAbsent(id, k -> new LinkedHashSet<>());
            if (z.has("area") && !z.get("area").getAsString().isBlank()) {
                String area = z.get("area").getAsString();
                zoneArea.put(id, area);
                if (!areaOrder.contains(area)) areaOrder.add(area);
            }
            if (z.has("connections") && z.get("connections").isJsonArray()) {
                for (JsonElement c : z.getAsJsonArray("connections")) {
                    String to = c.getAsString();
                    if (to != null && !to.isBlank()) adj.get(id).add(to);
                }
            }
        }
        // 연결을 양방향으로 보정 (한쪽에만 적힌 경우 대비)
        for (String a : new ArrayList<>(adj.keySet()))
            for (String b : new ArrayList<>(adj.get(a)))
                if (zoneNames.containsKey(b)) adj.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);

        hierarchical = areaOrder.size() >= 2;
        if (hierarchical) buildAreaGraph();
        computeLayouts();
    }

    /** 구역(area) 인접 관계 = 구역을 넘나드는 zone 연결에서 자동 도출 */
    private void buildAreaGraph() {
        for (String a : areaOrder) areaAdj.put(a, new LinkedHashSet<>());
        for (String z : zoneOrder) {
            String za = zoneArea.get(z);
            if (za == null) continue;
            for (String nb : adj.getOrDefault(z, Set.of())) {
                String na = zoneArea.get(nb);
                if (na != null && !na.equals(za)) { areaAdj.get(za).add(na); areaAdj.get(na).add(za); }
            }
        }
    }

    /** 세션 종료 시 약도 상태 초기화 (다음 세션에서 새 MapView 생성). */
    public void clear() {
        view = null;
        lastSig.clear();
        zoneNames.clear(); zoneArea.clear(); adj.clear(); zoneOrder = new ArrayList<>();
        areaOrder.clear(); areaAdj.clear(); overviewLayout.clear(); detailLayouts.clear(); flatLayout.clear();
        hierarchical = false;
    }

    public boolean hasZones() { return !zoneOrder.isEmpty(); }

    // ──────────────────────────────────────────────────────────────
    //  배치 (BFS 순서 그리드 — 128px에 한글 방이름이 겹치지 않게)
    // ──────────────────────────────────────────────────────────────

    private void computeLayouts() {
        if (hierarchical) {
            // 대분류: 상단 띠
            putAll(overviewLayout, grid(bfs(areaOrder, areaAdj), 2, 3, SIZE - 4, 30, Math.min(4, areaOrder.size())));
            // 소분류: 구역별 하단 영역
            for (String area : areaOrder) {
                List<String> zs = new ArrayList<>();
                for (String z : zoneOrder) if (area.equals(zoneArea.get(z))) zs.add(z);
                detailLayouts.put(area, grid(bfs(zs, adj), 2, 56, SIZE - 4, 70, 2));
            }
        } else {
            putAll(flatLayout, grid(bfs(zoneOrder, adj), 0, 0, SIZE, SIZE, 2));
        }
    }

    private static void putAll(Map<String, int[]> dst, Map<String, int[]> src) { dst.putAll(src); }

    /** 연결된 노드가 그리드에서 가깝게 오도록 BFS 순서로 나열 */
    private static List<String> bfs(List<String> nodes, Map<String, Set<String>> graph) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String start : nodes) {
            if (seen.contains(start)) continue;
            Deque<String> q = new ArrayDeque<>();
            q.add(start); seen.add(start);
            while (!q.isEmpty()) {
                String cur = q.poll();
                out.add(cur);
                for (String nb : graph.getOrDefault(cur, Set.of()))
                    if (!seen.contains(nb) && nodes.contains(nb)) { seen.add(nb); q.add(nb); }
            }
        }
        return out;
    }

    /** order를 (x0,y0,w,h) 영역에 최대 maxCols열 그리드로 배치 */
    private static Map<String, int[]> grid(List<String> order, int x0, int y0, int w, int h, int maxCols) {
        Map<String, int[]> m = new HashMap<>();
        int n = order.size();
        if (n == 0) return m;
        int cols = Math.min(Math.max(1, maxCols), n);
        int rows = (int) Math.ceil(n / (double) cols);
        int cw = w / cols, ch = h / rows;
        for (int i = 0; i < n; i++) {
            int c = i % cols, r = i / cols;
            m.put(order.get(i), new int[]{x0 + c * cw + cw / 2, y0 + r * ch + ch / 2});
        }
        return m;
    }

    private static int cols(int n, int maxCols) { return Math.min(Math.max(1, maxCols), Math.max(1, n)); }

    /** 화면 너비(maxW)에 맞게 자르고 넘치면 말줄임표(…) */
    private static String fit(FontMetrics fm, String s, int maxW) {
        if (s == null) return "";
        if (fm.stringWidth(s) <= maxW) return s;
        for (int len = s.length() - 1; len >= 1; len--) {
            String t = s.substring(0, len) + "…";
            if (fm.stringWidth(t) <= maxW) return t;
        }
        return "…";
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ──────────────────────────────────────────────────────────────
    //  약도 입수
    // ──────────────────────────────────────────────────────────────

    /** /trpg map — 직접 그린 약도(방문 zone만)를 손에 넣는다. 이미 있으면 안내만. */
    public void giveMapItem(Player p) {
        PlayerData pd = state.getPlayer(p);
        if (pd == null) { p.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        if (zoneOrder.isEmpty()) { p.sendMessage("§7아직 지도로 그릴 장소 정보가 없습니다."); return; }

        if (pd.zone != null && !pd.zone.isBlank()) pd.visitedZones.add(pd.zone);
        if (!pd.hasFullMap && pd.visitedZones.isEmpty()) {
            p.sendMessage("§7아직 가 본 곳이 없어 약도를 그릴 수 없습니다.");
            return;
        }
        ensureView(p.getWorld());
        if (hasOurMap(p)) {
            lastSig.remove(p.getUniqueId()); // 강제 갱신
            p.sendMessage("§7약도는 이미 손에 있습니다. §8(탐색할수록 자동으로 채워집니다)");
            return;
        }
        ItemStack item = buildMapItem();
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        leftover.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        p.sendMessage("§a약도를 손에 넣었습니다. §7인벤토리에서 펼쳐 확인하세요. (현위치는 §c깃발§7로 표시)");
    }

    /** 지도 입수(전체 공개) — &lt;MAP_GRANT&gt; 처리 시 호출. */
    public void grantFullMap(Player p) {
        PlayerData pd = state.getPlayer(p);
        if (pd == null) return;
        pd.hasFullMap = true;
        lastSig.remove(p.getUniqueId());
        ensureView(p.getWorld());
        if (!hasOurMap(p)) {
            ItemStack item = buildMapItem();
            Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
            leftover.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
        }
        p.sendMessage("§a지도를 입수했습니다. §7전체 구역이 약도에 드러납니다.");
    }

    private void ensureView(World w) {
        if (view != null) return;
        view = Bukkit.createMap(w);
        new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        try { view.setLocked(true); } catch (Throwable ignored) {}
        view.addRenderer(new Renderer());
    }

    private boolean hasOurMap(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != Material.FILLED_MAP || !it.hasItemMeta()) continue;
            if (it.getItemMeta().getPersistentDataContainer()
                  .has(mapKey, PersistentDataType.BYTE)) return true;
        }
        return false;
    }

    private ItemStack buildMapItem() {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta == null) return item;
        meta.setMapView(view);
        meta.displayName(Component.text("§e현장 약도"));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7[지도]"));
        lore.add(Component.text("§f펼쳐서 구역과 통로를 확인합니다."));
        lore.add(Component.text("§c깃발 §7= 현재 위치"));
        lore.add(Component.text("§8탐색할수록 가 본 곳이 채워집니다."));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(mapKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ──────────────────────────────────────────────────────────────
    //  렌더러 (viewer별 공개 범위 + 현위치 깃발)
    // ──────────────────────────────────────────────────────────────

    private class Renderer extends MapRenderer {
        Renderer() { super(true); } // contextual — 플레이어별 렌더

        @Override
        public void render(MapView v, MapCanvas canvas, Player viewer) {
            PlayerData pd = state.getPlayer(viewer);
            if (pd == null) return;
            boolean full = pd.hasFullMap;
            Set<String> revealed = full ? new LinkedHashSet<>(zoneOrder)
                                        : new LinkedHashSet<>(pd.visitedZones);
            revealed.retainAll(zoneNames.keySet());
            String cur = (pd.zone == null) ? "" : pd.zone;

            String sig = full + "|" + cur + "|" + new TreeSet<>(revealed);
            if (sig.equals(lastSig.get(viewer.getUniqueId()))) return;
            lastSig.put(viewer.getUniqueId(), sig);

            canvas.drawImage(0, 0, draw(revealed, cur));
        }
    }

    /** 공개된 zone과 통로, 현위치 깃발을 128x128 이미지로 그린다. */
    private BufferedImage draw(Set<String> revealed, String currentZone) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(C_BG);
        g.fillRect(0, 0, SIZE, SIZE);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        if (revealed.isEmpty()) {
            String msg = fit(fm, "약도 정보 없음", SIZE - 8);
            g.setColor(C_TEXT);
            g.drawString(msg, (SIZE - fm.stringWidth(msg)) / 2, SIZE / 2);
            g.dispose();
            return img;
        }

        if (hierarchical) drawHierarchical(g, fm, revealed, currentZone);
        else {
            int mbw = SIZE / cols(zoneOrder.size(), 2) - 6;
            drawGraph(g, fm, revealed, flatLayout, adj, currentZone, zoneNames, mbw, 0, 0, SIZE, SIZE);
        }
        g.dispose();
        return img;
    }

    private void drawHierarchical(Graphics2D g, FontMetrics fm, Set<String> revealed, String currentZone) {
        String curArea = zoneArea.get(currentZone);
        if (curArea == null && !areaOrder.isEmpty()) curArea = areaOrder.get(0);

        // 1) 대분류(구역) — 상단 띠
        Set<String> revealedAreas = new LinkedHashSet<>();
        for (String z : revealed) { String a = zoneArea.get(z); if (a != null) revealedAreas.add(a); }
        if (curArea != null) revealedAreas.add(curArea);
        Map<String, String> areaNames = new LinkedHashMap<>();
        for (String a : areaOrder) areaNames.put(a, a);
        int ovBoxW = (SIZE - 4) / cols(areaOrder.size(), 4) - 6;
        drawGraph(g, fm, revealedAreas, overviewLayout, areaAdj, curArea, areaNames, ovBoxW, 2, 3, SIZE - 4, 30);

        // 2) 구분선 + 현재 구역 표시(브레드크럼)
        g.setColor(C_DIV);
        g.drawLine(0, 38, SIZE, 38);
        g.setColor(C_TEXT);
        g.drawString(fit(fm, "▸ " + (curArea == null ? "?" : curArea), SIZE - 8), 4, 52);

        // 3) 소분류(현재 구역의 방) — 하단
        Map<String, int[]> dPos = detailLayouts.get(curArea);
        if (dPos == null) return;
        Set<String> revDetail = new LinkedHashSet<>();
        for (String z : revealed) if (Objects.equals(curArea, zoneArea.get(z))) revDetail.add(z);
        drawGraph(g, fm, revDetail, dPos, adj, currentZone, zoneNames, SIZE / 2 - 6, 2, 56, SIZE - 4, 70);
    }

    /** 한 그래프(노드+통로+현위치 깃발)를 지정 영역(rx,ry,rw,rh) 안에 그린다 */
    private void drawGraph(Graphics2D g, FontMetrics fm, Set<String> revealed, Map<String, int[]> pos,
                           Map<String, Set<String>> graph, String cur, Map<String, String> names,
                           int maxBoxW, int rx, int ry, int rw, int rh) {
        // 통로(연결선)
        g.setColor(C_EDGE);
        for (String a : revealed) {
            int[] pa = pos.get(a);
            if (pa == null) continue;
            for (String b : graph.getOrDefault(a, Set.of())) {
                if (!revealed.contains(b) || a.compareTo(b) >= 0) continue; // 중복 방지
                int[] pb = pos.get(b);
                if (pb == null) continue;
                g.drawLine(pa[0], pa[1], pb[0], pb[1]);
            }
        }
        // 방 박스 + 이름 + 현위치 깃발
        for (String id : revealed) {
            int[] p = pos.get(id);
            if (p == null) continue;
            String label = fit(fm, names.getOrDefault(id, id), maxBoxW - 8);
            int tw = fm.stringWidth(label);
            int bw = tw + 8, bh = fm.getAscent() + 4;
            int bx = clamp(p[0] - bw / 2, rx, rx + rw - bw);
            int by = clamp(p[1] - bh / 2, ry, ry + rh - bh);
            g.setColor(id.equals(cur) ? C_BOX2 : C_BOX); g.fillRect(bx, by, bw, bh);
            g.setColor(C_BORDER);                          g.drawRect(bx, by, bw, bh);
            g.setColor(C_TEXT);                            g.drawString(label, bx + 4, by + fm.getAscent());
            if (id.equals(cur)) {
                int px = bx + 4, topY = by - 1;
                g.setColor(C_POLE);
                g.drawLine(px, topY, px, topY - 11);
                g.setColor(C_FLAG);
                g.fillPolygon(new int[]{px, px + 8, px}, new int[]{topY - 11, topY - 8, topY - 5}, 3);
            }
        }
    }
}
