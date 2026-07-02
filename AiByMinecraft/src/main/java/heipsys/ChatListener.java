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
import org.bukkit.event.player.PlayerJoinEvent;
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

    /** 채팅에서 탭 → 아는 연락처(이름·번호)·@전체 자동완성. 빈 입력에서 탭만 눌러도 전체 후보 표시. */
    @EventHandler
    public void onChatTabComplete(com.destroystokyo.paper.event.server.AsyncTabCompleteEvent event) {
        if (event.isCommand()) return; // 명령어 자동완성은 제외 (채팅만)
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        if (!(event.getSender() instanceof Player player)) return;
        String buffer = event.getBuffer() == null ? "" : event.getBuffer();
        int lastSpace = buffer.lastIndexOf(' ');
        String lastWord = buffer.substring(lastSpace + 1);
        java.util.List<String> all = trpgManager.commSuggestions(player);
        if (all.isEmpty()) return;
        java.util.List<String> sugg = new java.util.ArrayList<>();
        if (lastWord.isEmpty()) {
            sugg.addAll(all); // 아무것도 안 친 상태에서 탭 → 전체 후보(@전체·이름·번호)
        } else if (lastWord.startsWith("@")) {
            for (String s : all) if (s.startsWith(lastWord)) sugg.add(s); // '@' 입력 중 → 접두사 필터
        } else {
            return; // 일반 단어 입력 중엔 개입하지 않음
        }
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

    /** 관전자가 인물을 클릭해 그 시점으로 '들어가면' 자동으로 대상 소지품 미러를 연다(웅크리기는 관전 중 동작 안 함). */
    @EventHandler
    public void onStartSpectating(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        if (!(event.getNewSpectatorTarget() instanceof Player)) return;
        Player spectator = event.getPlayer();
        // 이벤트 시점엔 getSpectatorTarget()이 아직 갱신 전이라 다음 틱에 연다.
        Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openSpectatorMirror(spectator));
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
        // 관전자 우클릭 → 보고 있는 대상의 소지품 미러(책·정보·기록 열람). 스펙테이터는 아이템이 없으므로 먼저 처리.
        if (player.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openSpectatorMirror(player));
            return;
        }
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

    /** 캐릭터 정보 / 기록 / TRPG 지급 아이템은 버릴 수 없음(버리기로 물리 제거되어 소지 상태와 어긋나던 문제 방지). */
    @EventHandler
    public void onInfoItemDrop(PlayerDropItemEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        var dropped = event.getItemDrop().getItemStack();
        if (trpgManager.isInfoItem(dropped) || trpgManager.isRecordItem(dropped) || trpgManager.isMapItem(dropped)
                || trpgManager.isTrpgItem(dropped)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§7소지품은 버릴 수 없습니다. (사용은 우클릭·행동으로)");
        }
    }

    /** TRPG 아이템·정보/기록/지도 아이템은 블록으로 설치할 수 없음(설치로 인벤에서 물리 제거되어 소지 상태와 어긋나던 문제 방지). */
    @EventHandler(ignoreCancelled = true)
    public void onTrpgItemPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || !trpgManager.isActive()) return;
        var inHand = event.getItemInHand();
        if (trpgManager.isTrpgItem(inHand) || trpgManager.isInfoItem(inHand)
                || trpgManager.isRecordItem(inHand) || trpgManager.isMapItem(inHand)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§7소지품은 설치할 수 없습니다. (사용은 우클릭·행동으로)");
        }
    }

    /** OP 접속 시, 진행 중 세션이 없는데 이어할 자동 저장이 있으면 안내(예기치 못한 중단 후 복구 유도). */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) return;
        TRPGGameManager trpgManager = trpg();
        if (trpgManager == null || trpgManager.isActive()) return;
        if (!trpgManager.hasAutoSave()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            player.sendMessage("§e[TRPG] 중단된 게임의 자동 저장 기록이 있습니다. §f/trpg resume §e으로 이어서 진행할 수 있습니다.");
        }, 40L);
    }

    /** 관전 인벤(대상 소지품 미러)은 읽기 전용 — 책=읽기, 정보★/기록책=대상 GUI(보기 전용)로 라우팅. */
    @EventHandler
    public void onSpectatorClickBook(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.SPECTATOR) return;
        event.setCancelled(true); // 관전 중 인벤 클릭은 아이템 이동 불가(열람 전용)
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        TRPGGameManager trpgManager = trpg();
        if (clicked.getType() == Material.WRITTEN_BOOK) {
            player.openBook(clicked);
        } else if (trpgManager != null && trpgManager.isInfoItem(clicked)) {
            Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openSpectatorInfo(player));
        } else if (trpgManager != null && trpgManager.isRecordItem(clicked)) {
            Bukkit.getScheduler().runTask(plugin, () -> trpgManager.openSpectatorRecords(player));
        }
    }
}
