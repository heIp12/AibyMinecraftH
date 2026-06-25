package heipsys.trpg;

import com.google.gson.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * 인게임 아이템 지급/회수 (STEP 5-1).
 * GM AI가 <ITEM_GRANT> 태그를 출력하면 .gdam의 key_items에서 데이터를 로드해 지급.
 * chapter_bound 아이템은 방 종료 시 자동 회수.
 */
public class ItemManager {

    private final Plugin           plugin;
    private final GameStateManager state;
    private final Gson             gson = new Gson();

    // 챕터 한정 아이템 추적: playerUUID → itemId 목록
    private final Map<UUID, List<String>> chapterBound = new HashMap<>();

    public ItemManager(Plugin plugin, GameStateManager state) {
        this.plugin = plugin;
        this.state  = state;
    }

    // ──────────────────────────────────────────────────────────────
    //  <ITEM_GRANT> 처리
    // ──────────────────────────────────────────────────────────────

    /**
     * AI 응답에서 파싱된 ITEM_GRANT JSON을 처리.
     * {"item_id":"item_001","player":"ALL 또는 플레이어명","chapter_bound":true}
     */
    public void processGrant(JsonObject grant, Collection<Player> activePlayers) {
        if (grant == null) return;
        String itemId       = grant.has("item_id")       ? grant.get("item_id").getAsString()       : "";
        String targetName   = grant.has("player")        ? grant.get("player").getAsString()        : "ALL";
        boolean chapBound   = !grant.has("chapter_bound") || grant.get("chapter_bound").getAsBoolean();

        JsonObject itemDef = findItemDef(itemId);
        if (itemDef == null) {
            plugin.getLogger().warning("[ItemManager] 알 수 없는 item_id: " + itemId);
            return;
        }

        for (Player p : activePlayers) {
            if (!"ALL".equalsIgnoreCase(targetName) && !p.getName().equals(targetName)) continue;
            giveItem(p, itemDef, chapBound);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  아이템 생성
    // ──────────────────────────────────────────────────────────────

    private void giveItem(Player player, JsonObject def, boolean chapBound) {
        String type  = def.has("type")  ? def.get("type").getAsString()  : "paper";
        String title = def.has("title") ? def.get("title").getAsString() : "알 수 없는 문서";
        String id    = def.has("id")    ? def.get("id").getAsString()    : UUID.randomUUID().toString().substring(0,8);

        ItemStack item = switch (type) {
            case "written_book" -> buildBook(def, title, player.getName());
            case "map"          -> buildPaper(def, title, true);
            default             -> buildPaper(def, title, false);
        };

        // 인벤토리에 추가 (메인 스레드에서 실행)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

            if (chapBound) {
                chapterBound.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>()).add(id);
            }
            state.collectItem(id);
        });
    }

    private ItemStack buildBook(JsonObject def, String title, String playerName) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return book;
        meta.setTitle(title);
        meta.setAuthor(playerName);
        meta.displayName(Component.text(title));

        if (def.has("pages")) {
            JsonArray pages = def.getAsJsonArray("pages");
            List<Component> pageComps = new ArrayList<>();
            for (JsonElement page : pages) {
                pageComps.add(Component.text(page.getAsString()));
            }
            meta.pages(pageComps);
        }
        book.setItemMeta(meta);
        return book;
    }

    private ItemStack buildPaper(JsonObject def, String title, boolean isMap) {
        ItemStack paper = new ItemStack(Material.PAPER);
        var meta = paper.getItemMeta();
        if (meta == null) return paper;
        meta.displayName(Component.text("§e" + title));

        // lore에 내용 추가
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7[TRPG 아이템]"));
        if (def.has("pages") && def.getAsJsonArray("pages").size() > 0) {
            String content = def.getAsJsonArray("pages").get(0).getAsString();
            // 40자씩 분리
            for (int i = 0; i < content.length(); i += 40) {
                lore.add(Component.text("§f" + content.substring(i, Math.min(i+40, content.length()))));
            }
        }
        meta.lore(lore);
        paper.setItemMeta(meta);
        return paper;
    }

    // ──────────────────────────────────────────────────────────────
    //  챕터 종료 시 회수
    // ──────────────────────────────────────────────────────────────

    public void reclaimChapterItems(Collection<Player> players) {
        for (Player p : players) {
            List<String> boundIds = chapterBound.getOrDefault(p.getUniqueId(), Collections.emptyList());
            if (boundIds.isEmpty()) continue;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                p.getInventory().forEach(item -> {
                    if (item == null) return;
                    var meta = item.getItemMeta();
                    if (meta == null) return;
                    // lore에 [TRPG 아이템] 표시가 있으면 회수
                    if (meta.hasLore()) {
                        meta.lore().stream()
                            .map(c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c))
                            .filter(s -> s.contains("[TRPG 아이템]"))
                            .findFirst()
                            .ifPresent(s -> p.getInventory().remove(item));
                    }
                });
                p.sendMessage("§7[챕터 종료] 챕터 아이템이 회수되었습니다.");
            });
        }
        chapterBound.clear();
    }

    // ──────────────────────────────────────────────────────────────
    //  .gdam에서 아이템 정의 로드
    // ──────────────────────────────────────────────────────────────

    private JsonObject findItemDef(String itemId) {
        JsonObject gdam = state.getGdamData();
        if (gdam == null || !gdam.has("key_items")) return null;
        for (JsonElement el : gdam.getAsJsonArray("key_items")) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("id") && obj.get("id").getAsString().equals(itemId)) return obj;
        }
        return null;
    }
}
