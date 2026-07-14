package heipsys.trpg;

import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * 인게임 아이템 지급/회수.
 * item_id가 key_items에 없으면 이름 키워드 기반으로 실제 마인크래프트 아이템으로 대체한다.
 */
public class ItemManager {

    private final Plugin           plugin;
    private final GameStateManager state;
    /** 챕터 종료 시 회수 대상 아이템에 붙는 보이지 않는 PDC 마커 키 */
    private final NamespacedKey    boundKey;
    /** 소모·제거 시 실제 인벤토리에서 그 아이템을 찾아내기 위한 아이템 id PDC 키 */
    private final NamespacedKey    idKey;

    public ItemManager(Plugin plugin, GameStateManager state) {
        this.plugin   = plugin;
        this.state    = state;
        this.boundKey = new NamespacedKey(plugin, "chapter_bound");
        this.idKey    = new NamespacedKey(plugin, "trpg_item_id");
    }

    // ──────────────────────────────────────────────────────────────
    //  <ITEM_GRANT> 처리
    // ──────────────────────────────────────────────────────────────

    public void processGrant(JsonObject grant, Collection<Player> activePlayers) {
        if (grant == null) return;
        String itemId     = grant.has("item_id")       ? grant.get("item_id").getAsString()       : "";
        String targetName = grant.has("player")        ? grant.get("player").getAsString()        : "ALL";
        boolean chapBound = !grant.has("chapter_bound") || grant.get("chapter_bound").getAsBoolean();

        JsonObject itemDef = findItemDef(itemId);

        for (Player p : activePlayers) {
            if (!"ALL".equalsIgnoreCase(targetName) && !p.getName().equals(targetName)) continue;
            giveItem(p, itemDef, itemId, chapBound);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  아이템 생성
    // ──────────────────────────────────────────────────────────────

    private void giveItem(Player player, JsonObject def, String fallbackName, boolean chapBound) {
        // def가 없으면 fallback 생성
        if (def == null) def = createFallbackDef(fallbackName);

        String type  = def.has("type")  ? def.get("type").getAsString()  : "paper";
        String title = def.has("title") ? def.get("title").getAsString()
                     : (def.has("name") ? def.get("name").getAsString()   : fallbackName);
        String id    = def.has("id")    ? def.get("id").getAsString()    : fallbackName;

        final JsonObject finalDef = def;
        ItemStack item;
        if (type.startsWith("physical:")) {
            item = buildPhysicalItem(type.substring(9), title, finalDef);
        } else {
            item = switch (type) {
                case "written_book" -> buildBook(finalDef, title, player.getName());
                case "map"          -> buildPaper(finalDef, title, true);
                default             -> buildPaper(finalDef, title, false);
            };
        }

        final String boundId = id;
        final boolean bound  = chapBound;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            tagItemId(item, boundId);            // 소모·제거 시 인벤토리에서 되찾을 수 있게 id 표식(항상)
            if (bound) tagChapterBound(item);    // 회수 마커 부착 — ★ItemMeta 변경은 메인 스레드에서★(replaySession 등 비동기 경로 대비)
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            state.collectItem(boundId);
            // Phase I: 읽을 수 있는 기록(record/content) 획득 → 그 단서를 '발견됨'으로 표식(엔딩 공개 연동)
            String itype = finalDef.has("item_type") ? finalDef.get("item_type").getAsString() : "";
            if (("record".equals(itype) || readBody(finalDef) != null)
                    && finalDef.has("clue_value") && !finalDef.get("clue_value").getAsString().isBlank()) {
                state.markFactDiscovered(finalDef.get("clue_value").getAsString());
            }
        });
    }

    /** 챕터 종료 시 회수 대상임을 아이템에 보이지 않게(PDC) 표시 — 모든 아이템 타입 공통 */
    private void tagChapterBound(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(boundKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
    }

    /** 아이템에 원본 id를 PDC로 심는다 — 소모(ITEM_USE consume)·제거(item_remove) 시 정확히 되찾아 없애기 위함. */
    private void tagItemId(ItemStack item, String id) {
        if (item == null || id == null || id.isBlank()) return;
        var meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
    }

    /** 이 ItemStack이 TRPG 지급 아이템인지(원본 id 표식 보유) — 설치·버리기로 물리 제거되는 걸 막는 판정용. */
    public boolean isTrpgItem(ItemStack item) {
        if (item == null) return false;
        var meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    /** 이 ItemStack의 TRPG 원본 id(표식). 없으면 "". */
    public String itemIdOf(ItemStack item) {
        if (item == null) return "";
        var meta = item.getItemMeta();
        if (meta == null) return "";
        String id = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id == null ? "" : id;
    }

    /**
     * ★소모·제거★: 플레이어 인벤토리에서 해당 id(또는 이름)의 아이템 실물을 제거한다.
     *  게임 로직(heldItemIds)만 지우고 실물이 남아 있던 문제를 해소. id PDC 우선, 없으면 표시 이름 매칭.
     */
    public void removeById(Player player, String id) {
        if (player == null || id == null || id.isBlank()) return;
        JsonObject def = findItemDef(id);
        String title = def != null ? (def.has("title") ? def.get("title").getAsString()
                                    : def.has("name") ? def.get("name").getAsString() : id) : id;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null) continue;
                var meta = it.getItemMeta();
                if (meta == null) continue;
                String pid = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
                boolean match = (pid != null && pid.equals(id));
                if (!match && pid == null && meta.hasDisplayName()) {
                    // 구형(표식 없는) 아이템 폴백: 표시 이름에 id/제목이 들어가면 매칭
                    String dn = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                    match = dn.contains(id) || (title != null && !title.isBlank() && dn.contains(title));
                }
                if (match) { player.getInventory().setItem(i, null); break; } // 한 개만 제거
            }
        });
    }

    /** lore에 붙는 동적 상태줄 표식(중복 삽입 방지·재갱신용). */
    private static final String STATUS_TAG = "§8[상태] ";

    /**
     * ★<ITEM_USE>로 상태가 바뀐 뒤, 인벤토리 실물 아이템의 이름·설명을 갱신★한다.
     *  - 소진·고장(broken)이면 이름이 '망가진 X'(회색)로 바뀐다(예: 칼 → 망가진 칼).
     *  - 잔량(charges)·켜짐(on)은 lore의 '[상태]' 한 줄로 표시(예: 손전등 '잔량 2').
     *  예전엔 지급 시점 외형이 고정돼, 상태(잔량/소진)가 GM 컨텍스트에만 있고 아이템엔 안 보였다.
     */
    public void refreshItemDisplay(Player player, String id, String baseName, boolean broken, boolean on, int charges) {
        if (player == null || id == null || id.isBlank()) return;
        JsonObject def = findItemDef(id);
        final String title = def != null ? (def.has("title") ? def.get("title").getAsString()
                                          : def.has("name") ? def.get("name").getAsString() : id) : id;
        final String base = (baseName != null && !baseName.isBlank()) ? baseName
                          : (title != null && !title.isBlank() ? title : id);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (ItemStack it : player.getInventory().getContents()) {
                if (it == null) continue;
                var meta = it.getItemMeta();
                if (meta == null) continue;
                String pid = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
                boolean match = (pid != null && pid.equals(id));
                if (!match && pid == null && meta.hasDisplayName()) { // 구형(표식 없는) 폴백: 표시 이름 매칭
                    String dn = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                    match = dn.contains(base) || dn.contains(id) || (title != null && !title.isBlank() && dn.contains(title));
                }
                if (!match) continue;
                meta.displayName(Component.text(broken ? "§8망가진 " + base : "§e" + base));
                // 기존 상태줄 제거 후, [TRPG 아이템]/[쪽지] 머리줄 다음에 새 상태줄 삽입(없으면 맨 앞).
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.removeIf(c -> PlainTextComponentSerializer.plainText().serialize(c).startsWith(STATUS_TAG));
                String status = statusLine(broken, on, charges);
                if (status != null) lore.add(Math.min(1, lore.size()), Component.text(status));
                meta.lore(lore);
                it.setItemMeta(meta);
                break; // 한 개만 갱신
            }
        });
    }

    /** 상태줄 문자열(잔량/켜짐/소진). 표시할 상태가 없으면 null. */
    private String statusLine(boolean broken, boolean on, int charges) {
        if (broken) return STATUS_TAG + "소진·고장 (작동 불가)";
        StringBuilder sb = new StringBuilder(STATUS_TAG);
        boolean any = false;
        if (on)           { sb.append("켜짐"); any = true; }
        if (charges >= 0) { if (any) sb.append(" · "); sb.append("잔량 ").append(charges); any = true; }
        return any ? sb.toString() : null;
    }

    // ──────────────────────────────────────────────────────────────
    //  아이템 빌더
    // ──────────────────────────────────────────────────────────────

    /** 키워드 기반으로 실제 마인크래프트 아이템 생성 (이름 변경) */
    private ItemStack buildPhysicalItem(String materialName, String title, JsonObject def) {
        Material mat;
        try {
            mat = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            mat = Material.PAPER;
        }
        ItemStack item = new ItemStack(mat);
        var meta = item.getItemMeta();
        if (meta == null) return item;
        meta.displayName(Component.text("§e" + title));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7[테네브로스 아이템]"));
        if (def.has("description")) {
            appendWrapped(lore, def.get("description").getAsString(), "§f");
        }
        // 주요 정보(lore_info)를 강조색으로 추가 — 물건형 아이템의 핵심 단서
        appendLoreInfo(lore, def);
        appendItemTypeHint(lore, def); // Phase I: 기계 효과 힌트
        // content(본문)가 있으면 lore에도 일부 노출 (쪽지·라벨류)
        appendContentToLore(lore, def);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildBook(JsonObject def, String title, String playerName) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;
        meta.setTitle(title);
        meta.setAuthor(playerName);
        meta.displayName(Component.text(title));
        List<Component> pageComps = new ArrayList<>();
        // content(신규) 우선, 없으면 pages(구) 호환
        JsonArray body = readBody(def);
        if (body != null) {
            for (JsonElement page : body) pageComps.add(Component.text(page.getAsString()));
        }
        if (pageComps.isEmpty() && def.has("description")) {
            pageComps.add(Component.text(def.get("description").getAsString()));
        }
        if (!pageComps.isEmpty()) meta.pages(pageComps);
        book.setItemMeta(meta);
        return book;
    }

    private ItemStack buildPaper(JsonObject def, String title, boolean isMap) {
        ItemStack paper = new ItemStack(isMap ? Material.MAP : Material.PAPER);
        var meta = paper.getItemMeta();
        if (meta == null) return paper;
        meta.displayName(Component.text("§e" + title));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7[" + (isMap ? "지도" : "쪽지") + "]"));
        // 쪽지·지도의 실제 본문을 lore에 줄바꿈해 표시 (마우스 오버로 읽음)
        JsonArray body = readBody(def);
        if (body != null) {
            for (JsonElement el : body) appendWrapped(lore, el.getAsString(), "§f");
        } else if (def.has("description")) {
            appendWrapped(lore, def.get("description").getAsString(), "§f");
        }
        appendLoreInfo(lore, def);
        appendItemTypeHint(lore, def); // Phase I: 기계 효과 힌트
        meta.lore(lore);
        paper.setItemMeta(meta);
        return paper;
    }

    /** content(신규) 또는 pages(구) 배열을 반환. 둘 다 없으면 null. */
    private JsonArray readBody(JsonObject def) {
        if (def.has("content") && def.get("content").isJsonArray()
            && def.getAsJsonArray("content").size() > 0) {
            return def.getAsJsonArray("content");
        }
        if (def.has("pages") && def.get("pages").isJsonArray()
            && def.getAsJsonArray("pages").size() > 0) {
            return def.getAsJsonArray("pages");
        }
        return null;
    }

    /** lore_info(주요 정보)를 강조색으로 lore에 추가 */
    private void appendLoreInfo(List<Component> lore, JsonObject def) {
        if (def.has("lore_info") && !def.get("lore_info").getAsString().isBlank()) {
            lore.add(Component.text("§6▸ 정보"));
            appendWrapped(lore, def.get("lore_info").getAsString(), "§e");
        }
    }

    /** item_type(기계 효과)이 있으면 짧은 기능 힌트를 lore에 표시 (Phase I) */
    private void appendItemTypeHint(List<Component> lore, JsonObject def) {
        if (def == null || !def.has("item_type")) return;
        String t = def.get("item_type").getAsString();
        if (t == null || t.isBlank()) return;
        String label = switch (t) {
            case "key"        -> "열쇠 — 잠긴 구역 해제";
            case "tool"       -> "도구 — 특정 행위 가능";
            case "light"      -> "조명 — 어둠 밝히기";
            case "weapon"     -> "무기 — 파괴·타격";
            case "consumable" -> "소모품 — 사용 시 효과";
            case "comm"       -> "통신 — 원격 연락";
            case "protective" -> "보호 — 위해 경감";
            case "ritual"     -> "의례 도구";
            case "evidence"   -> "증거 — 제시용";
            case "record"     -> "기록 — 읽어 단서 확보";
            case "combine"    -> "부품 — 조합 가능";
            default           -> "";
        };
        if (!label.isBlank()) lore.add(Component.text("§b▸ " + label));
    }

    /** 물건형 아이템에 짧은 content가 있으면 lore에 미리보기로 표시 */
    private void appendContentToLore(List<Component> lore, JsonObject def) {
        JsonArray body = readBody(def);
        if (body == null) return;
        lore.add(Component.text("§7― 적힌 내용 ―"));
        for (JsonElement el : body) appendWrapped(lore, el.getAsString(), "§f");
    }

    /** 긴 문자열을 38자 단위로 줄바꿈해 lore에 추가 */
    private void appendWrapped(List<Component> lore, String text, String color) {
        if (text == null || text.isBlank()) return;
        for (int i = 0; i < text.length(); i += 38) {
            lore.add(Component.text(color + text.substring(i, Math.min(i + 38, text.length()))));
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  아이템 검색 및 폴백
    // ──────────────────────────────────────────────────────────────

    /** key_items에서 아이템 정의를 찾는다 (런타임 ItemInstance 생성용). 없으면 null. */
    public JsonObject findDef(String itemId) { return findItemDef(itemId); }

    private JsonObject findItemDef(String itemId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("key_items")) return null;
        // id 정확 일치
        for (JsonElement el : gdam.getAsJsonArray("key_items")) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("id") && obj.get("id").getAsString().equals(itemId)) return obj;
        }
        // name 부분 일치 (GM이 name을 id로 출력하는 경우)
        for (JsonElement el : gdam.getAsJsonArray("key_items")) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("name")) {
                String name = obj.get("name").getAsString();
                if (name.equalsIgnoreCase(itemId) || name.contains(itemId) || itemId.contains(name))
                    return obj;
            }
        }
        return null; // → 호출부에서 createFallbackDef 사용
    }

    /** 아이템 정의 없을 때 키워드로 마인크래프트 머터리얼 결정해 임시 정의 생성 */
    private JsonObject createFallbackDef(String itemId) {
        String material = resolveMaterial(itemId);
        JsonObject def = new JsonObject();
        def.addProperty("id",   itemId);
        def.addProperty("name", itemId);
        def.addProperty("title", itemId);
        def.addProperty("type", "physical:" + material);
        plugin.getLogger().info("[ItemManager] 미정의 아이템 자동 매핑 → " + material); // ★스포 방지 — 아이템 이름은 로그 제외
        return def;
    }

    private String resolveMaterial(String name) {
        String low = name.toLowerCase();
        if (any(low, "열쇠", "키", "key", "마스터")) return "TRIPWIRE_HOOK";
        if (any(low, "손전등", "플래시", "랜턴", "전등", "torch", "light", "lamp")) return "LANTERN";
        if (any(low, "지도", "도면", "설계도", "blueprint")) return "MAP";
        if (any(low, "일지", "노트", "수첩", "다이어리", "diary", "notebook")) return "BOOK";
        if (any(low, "책", "book", "보고서", "계약서", "문서", "document")) return "BOOK";
        if (any(low, "종이", "쪽지", "편지", "letter", "paper", "카드", "card")) return "PAPER";
        if (any(low, "칼", "나이프", "knife", "단검", "dagger")) return "IRON_SWORD";
        if (any(low, "총", "권총", "소총", "rifle", "gun", "pistol", "firearm")) return "CROSSBOW";
        if (any(low, "핸드폰", "스마트폰", "휴대폰", "phone", "smartphone")) return "CLOCK";
        if (any(low, "무전기", "라디오", "radio", "walkie")) return "REPEATER";
        if (any(low, "약", "medicine", "치료제", "응급")) return "POTION";
        if (any(low, "배지", "뱃지", "badge", "신분증", "id card")) return "GOLD_NUGGET";
        if (any(low, "현금", "지폐", "동전", "돈", "money", "cash")) return "GOLD_INGOT";
        if (any(low, "사진", "photo", "그림", "portrait", "image")) return "MAP";
        if (any(low, "반지", "링", "ring")) return "GOLD_INGOT";
        if (any(low, "목걸이", "necklace", "체인", "chain")) return "CHAIN";
        if (any(low, "라이터", "성냥", "lighter", "match")) return "FLINT_AND_STEEL";
        if (any(low, "줄", "로프", "rope", "끈", "string")) return "STRING";
        if (any(low, "배터리", "battery", "충전기")) return "REDSTONE";
        if (any(low, "안경", "렌즈", "glasses")) return "GLASS_PANE";
        if (any(low, "시계", "watch", "clock")) return "CLOCK";
        if (any(low, "usb", "드라이브", "메모리", "disk")) return "COMPARATOR";
        if (any(low, "음식", "식품", "food", "빵", "bread")) return "BREAD";
        if (any(low, "물", "water", "음료", "juice", "drink")) return "GLASS_BOTTLE";
        if (any(low, "가방", "bag", "배낭", "backpack")) return "LEATHER";
        if (any(low, "테이프", "duct tape", "접착")) return "GRAY_WOOL";
        if (any(low, "도끼", "axe", "도끼")) return "IRON_AXE";
        if (any(low, "망치", "hammer")) return "IRON_HOE";
        if (any(low, "삽", "shovel")) return "IRON_SHOVEL";
        return "PAPER";
    }

    private boolean any(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    // ──────────────────────────────────────────────────────────────
    //  챕터 종료 시 회수
    // ──────────────────────────────────────────────────────────────

    public void reclaimChapterItems(Collection<Player> players) {
        for (Player p : players) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                ItemStack[] contents = p.getInventory().getContents();
                boolean removed = false;
                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item == null) continue;
                    var meta = item.getItemMeta();
                    if (meta != null
                        && meta.getPersistentDataContainer().has(boundKey, PersistentDataType.BYTE)) {
                        p.getInventory().setItem(i, null);  // 슬롯 단위 정확 제거 (책/쪽지/지도 포함)
                        removed = true;
                    }
                }
                if (removed) p.sendMessage("§7[챕터 종료] 챕터 아이템이 회수되었습니다.");
            });
        }
    }
}
