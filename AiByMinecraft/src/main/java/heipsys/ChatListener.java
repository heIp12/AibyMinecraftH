package heipsys;

import heipsys.trpg.TRPGGameManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class ChatListener implements Listener {

    private final AICraft plugin;

    public ChatListener(AICraft plugin) {
        this.plugin = plugin;
    }

    /** 리로드로 매니저가 교체되어도 항상 최신 인스턴스를 참조한다 */
    private TRPGGameManager trpg() {
        return plugin.trpgManager;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;

        Player player  = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () ->
            trpgManager.handleChat(player, message));
    }

    /** 채팅에서 '@' 입력 후 탭 → 아는 연락처(이름·번호)·@전체 자동완성 */
    @EventHandler
    public void onChatTabComplete(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event) {
        if (event.isCommand()) return; // 명령어 자동완성은 제외 (채팅만)
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        if (!(event.getSender() instanceof Player player)) return;
        String buffer = event.getBuffer();
        if (buffer == null || buffer.indexOf('@') < 0) return;
        int lastSpace = buffer.lastIndexOf(' ');
        String lastWord = buffer.substring(lastSpace + 1);
        if (!lastWord.startsWith("@")) return;
        java.util.List<String> sugg = new java.util.ArrayList<>();
        for (String s : trpgManager.commSuggestions(player)) if (s.startsWith(lastWord)) sugg.add(s);
        if (!sugg.isEmpty()) event.setCompletions(sugg);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        event.getDrops().removeIf(item -> item != null && item.getType() == Material.WRITTEN_BOOK);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isDead()) player.spigot().respawn();
        });
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        if (!event.isSneaking()) return;
        trpgManager.getNarrativeDelivery().onSneak(event.getPlayer());
    }

    /** 캐릭터 정보 / 기록 아이템 우클릭 → 해당 GUI 열기 */
    @EventHandler
    public void onInfoItemUse(PlayerInteractEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        if (event.getHand() != EquipmentSlot.HAND) return; // 양손 중복 방지
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (trpgManager.isInfoItem(event.getItem())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openCharacterInfo(player));
        } else if (trpgManager.isRecordItem(event.getItem())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openRecords(player));
        } else if (trpgManager.isMapItem(event.getItem())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openMapSelector(player));
        }
    }

    /** 캐릭터 정보 / 기록 아이템은 버릴 수 없음 */
    @EventHandler
    public void onInfoItemDrop(PlayerDropItemEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        var dropped = event.getItemDrop().getItemStack();
        if (trpgManager.isInfoItem(dropped) || trpgManager.isRecordItem(dropped) || trpgManager.isMapItem(dropped)) event.setCancelled(true);
    }

    @EventHandler
    public void onSpectatorClickBook(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.SPECTATOR) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked != null && clicked.getType() == Material.WRITTEN_BOOK) {
            event.setCancelled(true);
            player.openBook(clicked);
        }
    }
}
