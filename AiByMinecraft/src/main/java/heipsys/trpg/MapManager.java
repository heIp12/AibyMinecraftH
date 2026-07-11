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

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.*;
import java.util.List;

/**
 * 현장 약도(지도 아이템) 관리.
 *
 * .gdam zones[].connections 그래프를 128x128 filled_map에 그린다.
 * - 구역(area)이 2개 이상이면 구역별 독립 MapView + 전체 개요 MapView를 생성.
 * - 우클릭 → 대분류 다이얼로그(TRPGGameManager.openMapSelector) → 구역 버튼 클릭 → swapMapView로 지도 교체.
 * - MapView는 retrySession 시에도 유지(endSession·clear()에서만 해제).
 */
public class MapManager {

    private static final int SIZE     = 128;
    private static final int HEADER_H = 20; // 구역/전체 헤더 높이(px)

    private static final Color C_BG     = new Color(0xE8, 0xDC, 0xB5);
    private static final Color C_EDGE   = new Color(0x6B, 0x4F, 0x2A);
    private static final Color C_BOX    = new Color(0xF6, 0xEF, 0xD2);
    private static final Color C_BOX2   = new Color(0xE6, 0xC9, 0x7A);
    private static final Color C_BORDER = new Color(0x3A, 0x2A, 0x12);
    private static final Color C_TEXT   = new Color(0x20, 0x18, 0x08);
    private static final Color C_FLAG   = new Color(0xCC, 0x22, 0x22);
    private static final Color C_POLE   = new Color(0x33, 0x26, 0x12);
    private static final Color C_DIV    = new Color(0x9A, 0x82, 0x55);

    private final Plugin           plugin;
    private final GameStateManager state;
    private final NamespacedKey    mapKey;
    private Font                   font;

    // 그래프 데이터 — loadScenario 시 재구축, views는 유지
    private final Map<String, String>      zoneNames = new LinkedHashMap<>();
    private final Map<String, String>      zoneArea  = new LinkedHashMap<>();
    private final Map<String, String>      zoneRealm = new LinkedHashMap<>(); // zone_id → realm(거대 분리 영역: 현실/꿈/화성 등). 기본 "" = 주 영역. realm 다르면 걸어서 못 넘는다(#190).
    private final Map<String, Set<String>> adj       = new HashMap<>();
    private List<String>                   zoneOrder = new ArrayList<>();
    private final List<String>             areaOrder = new ArrayList<>();
    private final Map<String, Set<String>> areaAdj   = new HashMap<>();

    // 레이아웃 — loadScenario 시 재계산
    private final Map<String, int[]>              flatLayout     = new HashMap<>();
    private final Map<String, int[]>              overviewLayout = new HashMap<>();
    private final Map<String, Map<String, int[]>> areaLayouts    = new HashMap<>();

    // Views — retrySession에도 유지, endSession(clear())에서만 해제
    private final Map<String, MapView> areaViews = new LinkedHashMap<>(); // "" = flat
    private MapView                    overviewView = null;
    private final Map<UUID, String>    lastSig = new HashMap<>();
    private final Map<UUID, String>    mapViewArea = new java.util.concurrent.ConcurrentHashMap<>(); // 스코어보드 범례용: 플레이어별 현재 보는 뷰(""=개요/플랫, area명=대분류)

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
            plugin.getLogger().warning("[map] 한글 폰트 로드 실패: " + e.getMessage());
        }
        if (font == null) font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    // ──────────────────────────────────────────────────────────────
    //  시나리오 로드 (retrySession에서도 호출됨 — views는 유지)
    // ──────────────────────────────────────────────────────────────

    public void loadScenario(JsonObject gdam) {
        zoneNames.clear(); zoneArea.clear(); zoneRealm.clear(); adj.clear();
        zoneOrder = new ArrayList<>();
        areaOrder.clear(); areaAdj.clear();
        flatLayout.clear(); overviewLayout.clear(); areaLayouts.clear();
        lastSig.clear(); // 기존 views 강제 재렌더

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
            if (z.has("realm") && !z.get("realm").getAsString().isBlank()) // 거대 분리 영역(#190) — 다르면 걸어서 못 넘음
                zoneRealm.put(id, z.get("realm").getAsString());
            if (z.has("connections") && z.get("connections").isJsonArray()) {
                for (JsonElement c : z.getAsJsonArray("connections")) {
                    String to = c.getAsString();
                    if (to != null && !to.isBlank()) adj.get(id).add(to);
                }
            }
        }
        // 단방향 연결 보정
        for (String a : new ArrayList<>(adj.keySet()))
            for (String b : new ArrayList<>(adj.get(a)))
                if (zoneNames.containsKey(b)) adj.computeIfAbsent(b, k -> new LinkedHashSet<>()).add(a);

        if (areaOrder.size() >= 2) buildAreaGraph();
        computeLayouts();
    }

    private void buildAreaGraph() {
        for (String a : areaOrder) areaAdj.put(a, new LinkedHashSet<>());
        for (String z : zoneOrder) {
            String za = zoneArea.get(z); if (za == null) continue;
            for (String nb : adj.getOrDefault(z, Set.of())) {
                String na = zoneArea.get(nb);
                if (na != null && !na.equals(za)) { areaAdj.get(za).add(na); areaAdj.get(na).add(za); }
            }
        }
    }

    private void computeLayouts() {
        if (areaOrder.size() >= 2) {
            putAll(overviewLayout, grid(bfs(areaOrder, areaAdj), 0, HEADER_H, SIZE, SIZE - HEADER_H, 2));
            for (String area : areaOrder) {
                List<String> zs = zonesInArea(area);
                areaLayouts.put(area, grid(bfs(zs, adj), 0, HEADER_H, SIZE, SIZE - HEADER_H, 2));
            }
        } else {
            putAll(flatLayout, grid(bfs(zoneOrder, adj), 0, 0, SIZE, SIZE, 2));
        }
    }

    /** 세션 완전 종료 시만 호출. retrySession에서는 호출하지 않아 views를 유지한다. */
    public void clear() {
        areaViews.clear(); overviewView = null; lastSig.clear();
        zoneNames.clear(); zoneArea.clear(); adj.clear(); zoneOrder = new ArrayList<>();
        areaOrder.clear(); areaAdj.clear();
        flatLayout.clear(); overviewLayout.clear(); areaLayouts.clear();
    }

    public boolean hasZones()       { return !zoneOrder.isEmpty(); }
    public boolean hasMultiAreas()  { return areaOrder.size() >= 2; }
    public List<String> areaNames() { return Collections.unmodifiableList(areaOrder); }
    /**
     * 플레이어가 아는(방문·현재구역) 대분류(area)만 areaOrder 순으로 반환 — 지도 다이얼로그가
     * 미발견 장소(백룸 등)를 노출해 스포하던 문제 방지. 지도 이미지(overview)와 ★같은 visibleAreas★ 기준.
     */
    public List<String> knownAreaNames(PlayerData pd) {
        if (pd == null) return new ArrayList<>();
        Set<String> vis = visibleAreas(pd, pd.hasFullMap);
        List<String> out = new ArrayList<>();
        for (String area : areaOrder) if (vis.contains(area)) out.add(area);
        return out;
    }

    /**
     * 두 zone이 같은 대분류(건물·시설)에 속하는가 — 구내방송(PA) 도달 범위 판정용.
     * ★보수적★: 단일 구역 시나리오(대분류 없음)거나 zone/매핑이 불명이면 true(막지 않음) — 방송이 조용히 끊기는 것 방지.
     * 두 zone이 ★확실히 서로 다른 대분류★일 때만 false.
     */
    public boolean sameArea(String zoneA, String zoneB) {
        if (!hasMultiAreas()) return true;
        if (zoneA == null || zoneA.isEmpty() || zoneB == null || zoneB.isEmpty()) return true;
        if (zoneA.equals(zoneB)) return true;
        String aa = zoneArea.get(zoneA), ab = zoneArea.get(zoneB);
        if (aa == null || ab == null) return true;
        return aa.equals(ab);
    }
    public boolean isMapItem(ItemStack it) { return isOurMap(it); }
    public Set<String> getAdjacentZones(String zoneId) {
        return Collections.unmodifiableSet(adj.getOrDefault(zoneId, Set.of()));
    }

    /** 구역의 realm(거대 분리 영역: 현실/꿈/화성 등). 없으면 "" = 주 영역. realm이 다르면 걸어서 오갈 수 없다(#190) — GM 서술 전이만. */
    public String realmOf(String zoneId) { return zoneRealm.getOrDefault(zoneId, ""); }

    /**
     * ★이동 경로(BFS, #190 이동 뒤집기)★ — from→to 인접 그래프 최단 경로의 '거쳐 갈 구역들'(from 제외, to 포함).
     * allowed가 null이 아니면 그 집합 안의 구역만 경유한다(아는 구역만 지나가기 — 단 목적지 to는 예외 허용).
     * 경로가 없으면 빈 리스트. 예: 집→마트가 비인접이면 [밖, 시장, 마트].
     */
    public List<String> shortestZonePath(String from, String to, Set<String> allowed) {
        if (from == null || to == null || from.equals(to) || !adj.containsKey(from)) return Collections.emptyList();
        String fromRealm = realmOf(from); // ★realm 경계는 걸어서 못 넘는다(#190)★ — 다른 영역(꿈·화성·이세계 등)은 이 경로에 안 잡힘(GM 서술 전이만).
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        java.util.Map<String, String> prev = new java.util.HashMap<>(); // 노드 → 직전 노드(경로 복원용)
        queue.add(from); prev.put(from, null);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(to)) break;
            for (String nb : adj.getOrDefault(cur, Set.of())) {
                if (prev.containsKey(nb)) continue;                                   // 이미 방문
                if (!realmOf(nb).equals(fromRealm)) continue;                         // 다른 realm은 걸어서 못 넘음(목적지라도)
                if (allowed != null && !allowed.contains(nb) && !nb.equals(to)) continue; // 아는 구역만 경유
                prev.put(nb, cur); queue.add(nb);
            }
        }
        if (!prev.containsKey(to)) return Collections.emptyList();                     // 경로 없음
        java.util.LinkedList<String> path = new java.util.LinkedList<>();
        for (String at = to; at != null && !at.equals(from); at = prev.get(at)) path.addFirst(at);
        return path;                                                                  // from 제외, to 포함
    }

    // ──────────────────────────────────────────────────────────────
    //  약도 지급
    // ──────────────────────────────────────────────────────────────

    /** /trpg map — 직접 그린 약도(방문 zone만). 이미 있으면 안내만. */
    public void giveMapItem(Player p) {
        PlayerData pd = state.getPlayer(p);
        if (pd == null)             { p.sendMessage("§c참여 중인 캐릭터가 없습니다."); return; }
        if (!mapAvailable())        { p.sendMessage("§7이곳은 지도라 할 것이 없다 — 스스로 길을 익히는 수밖에 없다."); return; }
        if (zoneOrder.isEmpty())    { p.sendMessage("§7아직 지도로 그릴 장소 정보가 없습니다."); return; }
        if (pd.zone != null && !pd.zone.isBlank()) pd.visitedZones.add(pd.zone);
        if (hasOurMap(p)) {
            lastSig.remove(p.getUniqueId());
            p.sendMessage("§7약도는 이미 손에 있습니다. §8(우클릭 → 구역 전환)"); return;
        }
        give(p, buildMapItem(pd, defaultView(p.getWorld()), "전체"));
        p.sendMessage("§a약도를 손에 넣었습니다. §7우클릭으로 구역 전환, 현위치는 §c깃발§7로 표시됩니다.");
    }

    /** 시작 시 자동 지급 — 이미 소지 중이면 재렌더만 트리거. 에러 메시지 없음. */
    public void giveStartMap(Player p) {
        if (!hasZones()) return;
        if (!mapAvailable()) return; // ★지도 없는 세계(constraints.map_available=false)★ — 시작 약도 자동지급도 하지 않는다
        lastSig.remove(p.getUniqueId());
        if (hasOurMap(p)) return;
        give(p, buildMapItem(state.getPlayer(p), defaultView(p.getWorld()), "전체"));
    }

    /** 시나리오에 '지도'라는 물건 자체가 존재하는가 — constraints.map_available (기본 true). false면 시작 약도·MAP_GRANT 모두 무효. */
    public boolean mapAvailable() {
        JsonObject gdam = state.getGdamData();
        if (gdam != null && gdam.has("constraints") && gdam.get("constraints").isJsonObject()) {
            JsonObject c = gdam.getAsJsonObject("constraints");
            if (c.has("map_available")) {
                try { return c.get("map_available").getAsBoolean(); } catch (Exception ignored) {}
            }
        }
        return true;
    }

    /** &lt;MAP_GRANT&gt;(범위 속성 없음) — 스토리에서 전체 지도 입수. */
    public void grantFullMap(Player p) {
        PlayerData pd = state.getPlayer(p); if (pd == null) return;
        if (!mapAvailable()) return; // 지도 없는 세계 — 입수 자체가 불가
        pd.hasFullMap = true;
        lastSig.remove(p.getUniqueId());
        if (!hasOurMap(p)) give(p, buildMapItem(pd, defaultView(p.getWorld()), "전체"));
        p.sendMessage("§a지도를 입수했습니다. §7전체 구역이 약도에 드러납니다.");
    }

    /** &lt;MAP_GRANT area/zones&gt; — 스토리에서 일부 구역만 지도에 공개(부분 지도). zoneIds는 resolveGrantZones로 해석된 실제 zone_id들. */
    public void grantPartialMap(Player p, java.util.List<String> zoneIds) {
        PlayerData pd = state.getPlayer(p); if (pd == null) return;
        if (!mapAvailable()) return;
        if (zoneIds == null || zoneIds.isEmpty()) return;
        int before = pd.mapRevealedZones.size();
        for (String z : zoneIds) if (z != null && zoneNames.containsKey(z)) pd.mapRevealedZones.add(z);
        if (pd.mapRevealedZones.size() == before) return; // 새로 드러난 게 없음
        lastSig.remove(p.getUniqueId());
        if (!hasOurMap(p)) give(p, buildMapItem(pd, defaultView(p.getWorld()), "전체"));
        p.sendMessage("§a지도 일부를 손에 넣었습니다. §7새로 드러난 구역이 약도에 표시됩니다.");
    }

    /** MAP_GRANT의 area/zones 속성 → 실제 zone_id 목록. area=대분류(그 안 모든 zone), zones=CSV(개별 zone).
     *  id 직매칭 우선, 실패 시 표시명(zoneNames 값)·대분류명으로도 관대하게 매칭한다. 아무것도 못 찾으면 빈 목록. */
    public java.util.List<String> resolveGrantZones(String area, String zonesCsv) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (zonesCsv != null && !zonesCsv.isBlank()) {
            for (String tok : zonesCsv.split(",")) {
                String z = resolveZoneToken(tok.trim());
                if (z != null) out.add(z);
            }
        }
        if (area != null && !area.isBlank()) {
            String a = resolveAreaToken(area.trim());
            if (a != null) { for (String z : zoneOrder) if (a.equals(zoneArea.get(z))) out.add(z); }
            else { String z = resolveZoneToken(area.trim()); if (z != null) out.add(z); } // area가 사실 zone명일 수도
        }
        return new java.util.ArrayList<>(out);
    }

    /** 토큰(zone_id 또는 표시명)을 zone_id로. 못 찾으면 null. */
    private String resolveZoneToken(String tok) {
        if (tok == null || tok.isEmpty()) return null;
        if (zoneNames.containsKey(tok)) return tok;                     // id 직매칭
        for (Map.Entry<String, String> e : zoneNames.entrySet())       // 표시명 매칭
            if (tok.equalsIgnoreCase(e.getValue())) return e.getKey();
        return null;
    }

    /** 토큰을 대분류(area)명으로. 못 찾으면 null. */
    private String resolveAreaToken(String tok) {
        if (tok == null || tok.isEmpty()) return null;
        for (String a : areaOrder) if (tok.equalsIgnoreCase(a)) return a;
        return null;
    }

    /**
     * 인벤토리의 약도 아이템을 지정 구역/전체 MapView로 교체 (없으면 새로 지급).
     * @param area null이거나 빈 문자열이면 전체/overview, 구역명이면 해당 구역 뷰
     */
    public void swapMapView(Player player, String area) {
        World w = player.getWorld();
        boolean useOverview = (area == null || area.isEmpty());
        mapViewArea.put(player.getUniqueId(), useOverview ? "" : area); // 스코어보드 범례가 현재 뷰를 따라가게
        MapView target = useOverview
            ? (hasMultiAreas() ? ensureOverview(w) : ensureAreaView("", w))
            : ensureAreaView(area, w);
        String label = useOverview ? "전체" : area;
        lastSig.remove(player.getUniqueId());
        replaceOrGive(player, target, label);
        // 약도 전환 안내 메시지는 채팅에 출력하지 않는다(요청) — 전환은 지도 아이템·제목으로만 표시.
    }

    private MapView defaultView(World w) {
        return hasMultiAreas() ? ensureOverview(w) : ensureAreaView("", w);
    }

    private MapView ensureAreaView(String area, World w) {
        return areaViews.computeIfAbsent(area, k -> {
            MapView v = Bukkit.createMap(w);
            initView(v);
            v.addRenderer(k.isEmpty() ? new FlatRenderer() : new AreaRenderer(k));
            return v;
        });
    }

    private MapView ensureOverview(World w) {
        if (overviewView == null) {
            overviewView = Bukkit.createMap(w);
            initView(overviewView);
            overviewView.addRenderer(new OverviewRenderer());
        }
        return overviewView;
    }

    private void initView(MapView v) {
        new ArrayList<>(v.getRenderers()).forEach(v::removeRenderer);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        try { v.setLocked(true); } catch (Throwable ignored) {}
    }

    private void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> lr = p.getInventory().addItem(item);
        lr.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    private void replaceOrGive(Player player, MapView target, String label) {
        PlayerData pd = state.getPlayer(player);
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!isOurMap(it)) continue;
            MapMeta mm = (MapMeta) it.getItemMeta();
            mm.setMapView(target);
            mm.displayName(mapItemName(pd, label));
            it.setItemMeta(mm);
            inv.setItem(i, it);
            return;
        }
        give(player, buildMapItem(pd, target, label));
    }

    /** 지도 아이템 표시명 — ★스포 방지★: 아는 대분류가 하나뿐이면 대분류 이름(현실 등)을 숨긴다
     *  (그 이름 자체가 '다른 세계·구역이 있다'는 스포일러). 대분류를 2개 이상 알게 되면 그때부터 표기. */
    private Component mapItemName(PlayerData pd, String rawLabel) {
        boolean isAreaName = rawLabel != null && areaOrder.contains(rawLabel);
        boolean reveal = !isAreaName || (pd != null && knownAreaNames(pd).size() >= 2);
        String shown = (reveal && rawLabel != null && !rawLabel.isEmpty()) ? " §7[" + rawLabel + "]" : "";
        return Component.text("§e현장 약도" + shown);
    }

    private boolean hasOurMap(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isOurMap(it)) return true;
        return false;
    }

    private boolean isOurMap(ItemStack it) {
        if (it == null || it.getType() != Material.FILLED_MAP || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(mapKey, PersistentDataType.BYTE);
    }

    private ItemStack buildMapItem(PlayerData pd, MapView view, String label) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta == null) return item;
        meta.setMapView(view);
        meta.displayName(mapItemName(pd, label));
        meta.lore(List.of(
            Component.text("§7[지도]"),
            Component.text("§f펼쳐서 구역과 통로를 확인합니다."),
            Component.text("§c깃발 §7= 현재 위치"),
            Component.text("§8우클릭 → 구역 전환")));
        meta.getPersistentDataContainer().set(mapKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ──────────────────────────────────────────────────────────────
    //  렌더러 (viewer별 contextual)
    // ──────────────────────────────────────────────────────────────

    private class FlatRenderer extends MapRenderer {
        FlatRenderer() { super(true); }
        @Override public void render(MapView v, MapCanvas c, Player viewer) {
            PlayerData pd = state.getPlayer(viewer); if (pd == null) return;
            boolean full = pd.hasFullMap;
            Set<String> rev = visibleZones(pd, full, null);
            String cur = pd.zone == null ? "" : pd.zone;
            String sig = "flat|" + full + "|" + cur + "|" + new TreeSet<>(rev);
            if (sig.equals(lastSig.get(viewer.getUniqueId()))) return;
            lastSig.put(viewer.getUniqueId(), sig);
            c.drawImage(0, 0, drawFlat(rev, cur));
        }
    }

    private class AreaRenderer extends MapRenderer {
        private final String area;
        AreaRenderer(String area) { super(true); this.area = area; }
        @Override public void render(MapView v, MapCanvas c, Player viewer) {
            PlayerData pd = state.getPlayer(viewer); if (pd == null) return;
            boolean full = pd.hasFullMap;
            boolean revealName = knownAreaNames(pd).size() >= 2; // 아는 대분류 2개+부터 이름 표기(스포 방지)
            Set<String> rev = visibleZones(pd, full, area);
            String cur = area.equals(zoneArea.get(pd.zone)) ? pd.zone : "";
            String sig = "area|" + area + "|" + full + "|" + cur + "|" + revealName + "|" + new TreeSet<>(rev);
            if (sig.equals(lastSig.get(viewer.getUniqueId()))) return;
            lastSig.put(viewer.getUniqueId(), sig);
            c.drawImage(0, 0, drawArea(area, rev, cur, revealName));
        }
    }

    private class OverviewRenderer extends MapRenderer {
        OverviewRenderer() { super(true); }
        @Override public void render(MapView v, MapCanvas c, Player viewer) {
            PlayerData pd = state.getPlayer(viewer); if (pd == null) return;
            boolean full = pd.hasFullMap;
            Set<String> revAreas = visibleAreas(pd, full);
            String curArea = pd.zone != null ? zoneArea.get(pd.zone) : null;
            // ★스포 방지★: 아는 대분류가 하나뿐이면 대분류 개요(현실/꿈 등 이름 노출) 대신
            //  그 구역의 zone 약도를 중립 헤더로 그린다 — 대분류를 2개+ 알게 되면 개요로 전환.
            if (revAreas.size() < 2 && curArea != null) {
                Set<String> rev = visibleZones(pd, full, curArea);
                String cur = pd.zone == null ? "" : pd.zone;
                String sig = "ov1|" + full + "|" + curArea + "|" + cur + "|" + new TreeSet<>(rev);
                if (sig.equals(lastSig.get(viewer.getUniqueId()))) return;
                lastSig.put(viewer.getUniqueId(), sig);
                c.drawImage(0, 0, drawArea(curArea, rev, cur, false));
                return;
            }
            String sig = "overview|" + full + "|" + curArea + "|" + new TreeSet<>(revAreas);
            if (sig.equals(lastSig.get(viewer.getUniqueId()))) return;
            lastSig.put(viewer.getUniqueId(), sig);
            c.drawImage(0, 0, drawOverview(revAreas, curArea));
        }
    }

    private Set<String> visibleZones(PlayerData pd, boolean full, String filterArea) {
        // ★방문한 구역 + 진입 시 공개된 인접 길목만★ 노출한다 — 단일 구역 시나리오라도 아직 안 가본
        //   '먼(비인접)' 방은 숨긴다(스포 방지, 사용자 요청). visitedZones엔 updatePlayerZone이 '들어선 구역 +
        //   같은 대분류·realm의 인접 구역(눈에 보이는 길목)'을 넣어두므로, 이것만으로 방문+인접이 그려진다.
        //   (예전엔 단일 구역이면 zoneOrder 전체를 깔아, 한 건물 시나리오에서 안 가본 방까지 다 보였다.)
        Set<String> base = full ? new LinkedHashSet<>(zoneOrder) : new LinkedHashSet<>(pd.visitedZones);
        if (!full) {
            if (pd.mapRevealedZones != null) base.addAll(pd.mapRevealedZones); // ★부분 지도★ 스토리에서 공개된(안 가본) 구역도 노출
            if (pd.zone != null && !pd.zone.isEmpty()) base.add(pd.zone); // 현위치는 방문기록 누락에 대비해 항상 포함
        }
        base.retainAll(zoneNames.keySet());
        if (filterArea != null) base.removeIf(z -> !filterArea.equals(zoneArea.get(z)));
        return base;
    }

    private Set<String> visibleAreas(PlayerData pd, boolean full) {
        if (full) return new LinkedHashSet<>(areaOrder);
        Set<String> areas = new LinkedHashSet<>();
        for (String z : pd.visitedZones) { String a = zoneArea.get(z); if (a != null) areas.add(a); }
        if (pd.mapRevealedZones != null)                                  // ★부분 지도★ 공개된 구역이 속한 대분류도 노출
            for (String z : pd.mapRevealedZones) { String a = zoneArea.get(z); if (a != null) areas.add(a); }
        if (pd.zone != null) { String a = zoneArea.get(pd.zone); if (a != null) areas.add(a); }
        return areas;
    }

    /** ★스코어보드 범례★ 지도를 든 플레이어의 '현재 보는 뷰'에 그려진 방(또는 대분류) 이름 목록.
     *  각 항목 "[n] 이름"(현위치는 §a▸), 번호는 지도 박스 [n]과 동일 순서. 계산 불가면 빈 목록. */
    public List<String> currentViewLabels(Player p) {
        PlayerData pd = state.getPlayer(p);
        if (pd == null) return List.of();
        boolean full = pd.hasFullMap;
        String view = mapViewArea.getOrDefault(p.getUniqueId(), "");
        if (!hasMultiAreas())                                    // FlatRenderer — 전체 zone
            return numberLabels(visibleZones(pd, full, null), zoneNames, pd.zone == null ? "" : pd.zone);
        if (view == null || view.isEmpty()) {                    // OverviewRenderer
            Set<String> revAreas = visibleAreas(pd, full);
            String curArea = pd.zone != null ? zoneArea.get(pd.zone) : null;
            if (revAreas.size() < 2 && curArea != null)          // 개요 폴백 → 현재 대분류의 방들
                return numberLabels(visibleZones(pd, full, curArea), zoneNames, pd.zone == null ? "" : pd.zone);
            Map<String, String> an = new LinkedHashMap<>();
            for (String a : areaOrder) if (revAreas.contains(a)) an.put(a, a);
            return numberLabels(revAreas, an, curArea == null ? "" : curArea); // 대분류 이름
        }
        String cur = view.equals(zoneArea.get(pd.zone)) ? pd.zone : ""; // AreaRenderer(view)
        return numberLabels(visibleZones(pd, full, view), zoneNames, cur);
    }

    /** id 집합을 "[n] 이름" 목록으로(순회 순서대로 1..N 번호). 현위치는 §a▸ 강조. drawGraph 박스 번호와 순서 일치.
     *  ★짧은 이름(≤4글자)은 지도에 직접 그려지므로 범례에서 생략★ — 단, 번호 n은 그대로 소비해 지도 [n]과 어긋나지 않게 한다. */
    private List<String> numberLabels(Set<String> ids, Map<String, String> names, String cur) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (String id : ids) {
            n++;
            String nm = names.getOrDefault(id, id);
            if (isShortMapName(nm)) continue; // 지도 박스에 이름이 직접 그려짐 → 범례 중복 생략(n은 이미 증가해 번호 일치 유지)
            if (nm.length() > 16) nm = nm.substring(0, 15) + "…";
            out.add(id.equals(cur) ? ("§a▸[" + n + "] " + nm) : ("§7[" + n + "] §f" + nm));
        }
        return out;
    }

    /** 지도 박스에 이름을 직접 그릴 만큼 짧은가(≤4글자). 짧으면 이름을, 길면 [n]을 그린다.
     *  drawGraph·numberLabels 양쪽이 이 판정을 공유해야 지도 [n] ↔ 범례 [n] 번호가 일치한다. */
    private static boolean isShortMapName(String nm) {
        if (nm == null) return false;
        String t = nm.trim();
        return !t.isEmpty() && t.length() <= 4;
    }

    // ──────────────────────────────────────────────────────────────
    //  이미지 생성
    // ──────────────────────────────────────────────────────────────

    private BufferedImage drawFlat(Set<String> rev, String cur) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        FontMetrics fm = g.getFontMetrics();
        if (rev.isEmpty()) drawEmpty(g, fm, SIZE / 2, SIZE / 2);
        else {
            int mbw = SIZE / Math.max(1, Math.min(2, zoneOrder.size())) - 6;
            drawGraph(g, fm, rev, flatLayout, adj, cur, zoneNames, mbw, 0, 0, SIZE, SIZE);
        }
        g.dispose(); return img;
    }

    private BufferedImage drawArea(String area, Set<String> rev, String cur, boolean revealName) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        FontMetrics fm = g.getFontMetrics();
        drawHeader(g, fm, revealName ? "▸ " + area : "▸ 현장 약도");
        if (rev.isEmpty()) drawEmpty(g, fm, SIZE / 2, (SIZE + HEADER_H) / 2);
        else {
            Map<String, int[]> layout = areaLayouts.getOrDefault(area, Map.of());
            int cols = Math.min(2, Math.max(1, zonesInArea(area).size()));
            int mbw = SIZE / cols - 6;
            drawGraph(g, fm, rev, layout, adj, cur, zoneNames, mbw, 0, HEADER_H, SIZE, SIZE - HEADER_H);
        }
        g.dispose(); return img;
    }

    private BufferedImage drawOverview(Set<String> revAreas, String curArea) {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = setup(img);
        FontMetrics fm = g.getFontMetrics();
        drawHeader(g, fm, "전체 구역 약도");
        if (revAreas.isEmpty()) drawEmpty(g, fm, SIZE / 2, (SIZE + HEADER_H) / 2);
        else {
            int cols = Math.min(2, Math.max(1, areaOrder.size()));
            int mbw = SIZE / cols - 6;
            Map<String, String> nameMap = new LinkedHashMap<>();
            for (String a : areaOrder) nameMap.put(a, a);
            drawGraph(g, fm, revAreas, overviewLayout, areaAdj, curArea, nameMap, mbw, 0, HEADER_H, SIZE, SIZE - HEADER_H);
        }
        g.dispose(); return img;
    }

    private Graphics2D setup(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(C_BG); g.fillRect(0, 0, SIZE, SIZE);
        g.setFont(font);
        return g;
    }

    private void drawHeader(Graphics2D g, FontMetrics fm, String text) {
        g.setColor(C_TEXT);
        g.drawString(fit(fm, text, SIZE - 4), 2, fm.getAscent() + 2);
        g.setColor(C_DIV);
        g.drawLine(0, HEADER_H - 2, SIZE, HEADER_H - 2);
    }

    private void drawEmpty(Graphics2D g, FontMetrics fm, int cx, int cy) {
        String msg = "약도 정보 없음";
        g.setColor(C_TEXT);
        g.drawString(fit(fm, msg, SIZE - 8), cx - fm.stringWidth(msg) / 2, cy);
    }

    private void drawGraph(Graphics2D g, FontMetrics fm,
                           Set<String> revealed, Map<String, int[]> pos,
                           Map<String, Set<String>> graph, String cur, Map<String, String> names,
                           int maxBoxW, int rx, int ry, int rw, int rh) {
        g.setColor(C_EDGE);
        for (String a : revealed) {
            int[] pa = pos.get(a); if (pa == null) continue;
            for (String b : graph.getOrDefault(a, Set.of())) {
                if (!revealed.contains(b) || a.compareTo(b) >= 0) continue;
                int[] pb = pos.get(b); if (pb == null) continue;
                g.drawLine(pa[0], pa[1], pb[0], pb[1]);
            }
        }
        // ★짧은 방 이름(≤4글자)은 지도에 직접 그린다★ — 긴 이름만 [n]으로 두고 스코어보드 범례(currentViewLabels)에서
        //   매칭한다(예전엔 긴 이름 글씨가 지도를 가리던 문제로 전부 [n]만 박았음 — 사용자 요청으로 짧은 이름은 복원).
        //   ★short 판정은 numberLabels와 반드시 동일해야★ 번호 n이 어긋나지 않는다(둘 다 revealed 순회 = 동일 순서).
        int n = 0;
        for (String id : revealed) {
            n++;
            int[] p = pos.get(id); if (p == null) continue;
            String nm = names.get(id);
            String label = isShortMapName(nm) ? nm.trim() : ("[" + n + "]");
            int tw = fm.stringWidth(label);
            int bw = Math.min(maxBoxW, tw + 8), bh = fm.getAscent() + 4;
            int bx = clamp(p[0] - bw / 2, rx, rx + rw - bw);
            int by = clamp(p[1] - bh / 2, ry, ry + rh - bh);
            g.setColor(id.equals(cur) ? C_BOX2 : C_BOX); g.fillRect(bx, by, bw, bh);
            g.setColor(C_BORDER);                          g.drawRect(bx, by, bw, bh);
            g.setColor(C_TEXT);                            g.drawString(fit(fm, label, bw - 6), bx + 4, by + fm.getAscent());
            if (id.equals(cur)) {
                int px = bx + 4, topY = by - 1;
                g.setColor(C_POLE); g.drawLine(px, topY, px, topY - 11);
                g.setColor(C_FLAG); g.fillPolygon(new int[]{px, px + 8, px}, new int[]{topY - 11, topY - 8, topY - 5}, 3);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  유틸
    // ──────────────────────────────────────────────────────────────

    private List<String> zonesInArea(String area) {
        List<String> zs = new ArrayList<>();
        for (String z : zoneOrder) if (area.equals(zoneArea.get(z))) zs.add(z);
        return zs;
    }

    private static void putAll(Map<String, int[]> dst, Map<String, int[]> src) { dst.putAll(src); }

    private static List<String> bfs(List<String> nodes, Map<String, Set<String>> graph) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String start : nodes) {
            if (seen.contains(start)) continue;
            Deque<String> q = new ArrayDeque<>(); q.add(start); seen.add(start);
            while (!q.isEmpty()) {
                String c = q.poll(); out.add(c);
                for (String nb : graph.getOrDefault(c, Set.of()))
                    if (!seen.contains(nb) && nodes.contains(nb)) { seen.add(nb); q.add(nb); }
            }
        }
        return out;
    }

    private static Map<String, int[]> grid(List<String> order, int x0, int y0, int w, int h, int maxCols) {
        Map<String, int[]> m = new HashMap<>();
        int n = order.size(); if (n == 0) return m;
        int cols = Math.min(Math.max(1, maxCols), n);
        int rows = (int) Math.ceil(n / (double) cols);
        int cw = w / cols, ch = h / rows;
        for (int i = 0; i < n; i++) {
            int c = i % cols, r = i / cols;
            m.put(order.get(i), new int[]{x0 + c * cw + cw / 2, y0 + r * ch + ch / 2});
        }
        return m;
    }

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
}
