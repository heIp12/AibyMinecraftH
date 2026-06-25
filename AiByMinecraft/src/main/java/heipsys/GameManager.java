package heipsys;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

public class GameManager {
    
    private final AICraft plugin;
    private final AIBattleManager aiManager;
    
    private boolean isGameRunning = false;
    private boolean isTournamentPhase = false; 
    
    private List<Player> alivePlayers = new ArrayList<>();
    private List<Player> nextRoundPlayers = new ArrayList<>();
    private final List<BattleSession> activeSessions = new ArrayList<>();
    
    private final Map<String, String> playerLegacies = new HashMap<>();
    
    private int tournamentRound = 1;
    private BossBar farmingBossBar;

    public GameManager(AICraft plugin, AIBattleManager aiManager) {
        this.plugin = plugin;
        this.aiManager = aiManager;
    }

    public void saveLegacy(Player player, String legacy) {
        if (legacy != null && !legacy.trim().isEmpty() && !legacy.equals("null")) {
            playerLegacies.put(player.getName(), legacy);
        }
    }

    public String getLegacy(Player player) {
        return playerLegacies.getOrDefault(player.getName(), "없음");
    }

    public void startGame(int timeInSeconds, boolean giveRandomItems, int randomItemCount) {
        if (isGameRunning) {
            Bukkit.broadcastMessage("§c이미 게임이 진행 중입니다!");
            return;
        }
        
        alivePlayers = Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                .collect(Collectors.toList());

        if (alivePlayers.size() < 2) {
            Bukkit.broadcastMessage("§c참여 가능한 서바이벌 플레이어가 2명 이상이어야 합니다.");
            return;
        }

        isGameRunning = true;
        isTournamentPhase = false; 
        tournamentRound = 1;
        playerLegacies.clear(); 
        
        List<Material> validItems = Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(m -> !m.isAir())
                .collect(Collectors.toList());
        Random random = new Random();
        
        World world = alivePlayers.get(0).getWorld();
        world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, false);
        world.setGameRule(org.bukkit.GameRule.LOCATOR_BAR, false);
        
        // [버그 수정] 파밍 시간(timeInSeconds)이 0초든 아니든 먼저 아이템부터 전부 지급합니다.
        for (Player p : alivePlayers) {
            p.getInventory().clear();
            p.getInventory().setArmorContents(null);
            p.getInventory().setItemInOffHand(null);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            
            if (giveRandomItems && randomItemCount > 0) {
                for (int i = 0; i < randomItemCount; i++) {
                    Material randomMat = validItems.get(random.nextInt(validItems.size()));
                    p.getInventory().addItem(new ItemStack(randomMat, 1));
                }
                p.sendMessage("§a[보급] §f무작위 아이템 " + randomItemCount + "개를 지급받았습니다!");
            } else {
                p.sendMessage("§a[보급] §f인벤토리가 초기화되었습니다.");
            }
        }

        if (timeInSeconds > 0) {
            Bukkit.broadcastMessage("§e[시스템] §f전장 생성을 위해 플레이어를 순차적으로 강하합니다...");
            
            new BukkitRunnable() {
                int index = 0;

                @Override
                public void run() {
                    if (index >= alivePlayers.size()) {
                        startFarmingTimer(timeInSeconds);
                        cancel();
                        return;
                    }

                    Player p = alivePlayers.get(index);
                    if (p.isOnline()) {
                        Location randomStartLoc = getRandomSurfaceLocation(world);
                        p.teleport(randomStartLoc);
                    }
                    index++;
                }
            }.runTaskTimer(plugin, 0L, 5L); 
            
        } else {
            // 파밍 시간이 0초면 텔레포트 대기 없이 바로 토너먼트 진입 (아이템은 이미 위에서 지급됨)
            startTournamentPhase();
        }
    }
    
    private void startFarmingTimer(int timeInSeconds) {
        int mins = timeInSeconds / 60;
        int secs = timeInSeconds % 60;
        Bukkit.broadcastMessage("§a[안내] §f모든 플레이어가 뿔뿔이 흩어져 파밍을 시작합니다! 제한 시간: " + mins + "분 " + secs + "초");

        String title = String.format("§c파밍 종료까지 남은 시간: %02d:%02d", mins, secs);
        farmingBossBar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
        for (Player p : alivePlayers) {
            farmingBossBar.addPlayer(p);
        }
        
        new BukkitRunnable() {
            int timeLeft = timeInSeconds;
            final int totalTime = timeInSeconds;

            @Override
            public void run() {
                if (!isGameRunning) {
                    farmingBossBar.removeAll();
                    cancel();
                    return;
                }

                timeLeft--;

                if (timeLeft <= 0) {
                    farmingBossBar.removeAll();
                    cancel();
                    startTournamentPhase();
                    return;
                }

                int m = timeLeft / 60;
                int s = timeLeft % 60;
                farmingBossBar.setTitle(String.format("§c파밍 종료까지 남은 시간: %02d:%02d", m, s));
                farmingBossBar.setProgress((double) Math.max(0, timeLeft) / totalTime);
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }
    
    private Location getRandomSurfaceLocation(World world) {
        Random random = new Random();
        int maxRadius = 3000; 
        
        for (int i = 0; i < 3; i++) {
            int x = random.nextInt(maxRadius * 2) - maxRadius;
            int z = random.nextInt(maxRadius * 2) - maxRadius;
            
            int y = world.getHighestBlockYAt(x, z);
            Material blockUnder = world.getBlockAt(x, y, z).getType();
            
            if (blockUnder.isSolid() && blockUnder != Material.LAVA && blockUnder != Material.WATER) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        
        int fallbackX = random.nextInt(maxRadius * 2) - maxRadius;
        int fallbackZ = random.nextInt(maxRadius * 2) - maxRadius;
        int fallbackY = world.getHighestBlockYAt(fallbackX, fallbackZ);
        return new Location(world, fallbackX + 0.5, fallbackY + 1, fallbackZ + 0.5);
    }
    
    private void startTournamentPhase() {
        isTournamentPhase = true;
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, true);
        }
        clearAllMonsters(); 
        Bukkit.broadcastMessage("§c[안내] §f파밍 시간이 종료되었습니다! 무작위 1대1 토너먼트를 시작합니다.");
        startNextRound();
    }
    
    private void clearAllMonsters() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast || entity instanceof Phantom) {
                    entity.remove();
                }
            }
        }
    }

    private void startNextRound() {
        alivePlayers.removeIf(p -> !p.isOnline());

        if (alivePlayers.size() <= 1) {
            if (alivePlayers.size() == 1) {
                Bukkit.broadcastMessage("§e[최종 결과] §a" + alivePlayers.get(0).getName() + "§f님이 최종 우승을 차지했습니다!");
                alivePlayers.get(0).setGameMode(GameMode.SURVIVAL);
            } else {
                Bukkit.broadcastMessage("§c[최종 결과] §f모두가 쓰러졌습니다. 생존자가 없습니다.");
            }
            isGameRunning = false;
            isTournamentPhase = false; 
            return;
        }

        Bukkit.broadcastMessage("§6============== [ 토너먼트 라운드 " + tournamentRound + " ] ==============");
        
        Collections.shuffle(alivePlayers);
        activeSessions.clear();
        nextRoundPlayers.clear();

        World world = alivePlayers.get(0).getWorld();

        List<Player[]> matchups = new ArrayList<>();
        for (int i = 0; i < alivePlayers.size(); i += 2) {
            if (i + 1 < alivePlayers.size()) {
                matchups.add(new Player[]{alivePlayers.get(i), alivePlayers.get(i + 1)});
            } else {
                matchups.add(new Player[]{alivePlayers.get(i)});
            }
        }

        Bukkit.broadcastMessage("§8[시스템] 새로운 전장을 탐색 중입니다. 잠시 대기해 주세요...");

        new BukkitRunnable() {
            int matchIndex = 0;

            @Override
            public void run() {
                if (matchIndex >= matchups.size()) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (getSessionByPlayer(p) == null) {
                            p.setGameMode(GameMode.SPECTATOR); 
                            teleportToSpectate(p);
                        }
                    }
                    tournamentRound++;
                    cancel();
                    return;
                }

                Player[] pair = matchups.get(matchIndex);
                if (pair.length == 2) {
                    Player p1 = pair[0];
                    Player p2 = pair[1];
                    
                    Location center = getRandomSurfaceLocation(world);
                    Location arena1 = center.clone().add(5, 0, 0);
                    Location arena2 = center.clone().add(-5, 0, 0);
                    
                    p1.setGameMode(GameMode.SURVIVAL);
                    p2.setGameMode(GameMode.SURVIVAL);
                    
                    p1.teleport(arena1);
                    p2.teleport(arena2);
                    String biomeName = world.getBiome(center).name();
                    
                    if(playerLegacies.containsKey(p1.getName())) p1.sendMessage("§d[서사 계승] §f이전 라운드의 업적을 이어받습니다.");
                    if(playerLegacies.containsKey(p2.getName())) p2.sendMessage("§d[서사 계승] §f이전 라운드의 업적을 이어받습니다.");

                    BattleSession session = new BattleSession(plugin, aiManager, GameManager.this, p1, p2, biomeName);
                    activeSessions.add(session);
                } else {
                    Player byePlayer = pair[0];
                    nextRoundPlayers.add(byePlayer);
                    Bukkit.broadcastMessage("§b[부전승] §f" + byePlayer.getName() + "님은 부전승입니다! 다른 배틀이 끝날 때까지 관전합니다.");
                }
                
                matchIndex++;
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    private void teleportToSpectate(Player spectator) {
        if (activeSessions.isEmpty()) return;
        
        Player targetToWatch = activeSessions.get(0).getPlayer1(); 
        double minDistance = Double.MAX_VALUE;
        
        for (BattleSession session : activeSessions) {
            Player p1 = session.getPlayer1();
            if (p1 != null && p1.isOnline() && p1.getWorld().equals(spectator.getWorld())) {
                double dist = p1.getLocation().distanceSquared(spectator.getLocation());
                if (dist < minDistance) {
                    minDistance = dist;
                    targetToWatch = p1;
                }
            }
        }
        
        if (targetToWatch != null) {
            Location specLoc = targetToWatch.getLocation().add(0, 8, 0);
            specLoc.setPitch(60f); 
            spectator.teleport(specLoc);
            spectator.sendMessage("§e[관전] §f진행 중인 배틀의 관전석으로 이동되었습니다.");
        }
    }

    public void onBattleEnd(BattleSession session, Player winner, Player loser, boolean draw, JsonObject storyJson) {
        activeSessions.remove(session);
        
        createAndDistributeBooks(session, storyJson, winner);
        if (storyJson.has("battle_summary")) {
            JsonObject summary = storyJson.getAsJsonObject("battle_summary");
            
            if (summary.has("special_win_reason") && !summary.get("special_win_reason").isJsonNull()) {
                Bukkit.broadcastMessage("§6[!] 특수 승리 발생: " + summary.get("special_win_reason").getAsString());
            }
            
            if (summary.has("p1_death_reason") && !summary.get("p1_death_reason").isJsonNull()) 
                Bukkit.broadcastMessage("§c[사망 요약] " + session.getPlayer1().getName() + ": " + summary.get("p1_death_reason").getAsString());
            if (summary.has("p2_death_reason") && !summary.get("p2_death_reason").isJsonNull()) 
                Bukkit.broadcastMessage("§c[사망 요약] " + session.getPlayer2().getName() + ": " + summary.get("p2_death_reason").getAsString());
        }
        
        if (draw) {
            Bukkit.broadcastMessage("§8[배틀 종료] §f동시 사망으로 무승부 처리되었습니다.");
            if (winner != null && winner.isOnline()) { winner.setGameMode(GameMode.SPECTATOR); teleportToSpectate(winner); }
            if (loser != null && loser.isOnline()) { loser.setGameMode(GameMode.SPECTATOR); teleportToSpectate(loser); }
        } else if (winner != null && winner.isOnline()) {
            nextRoundPlayers.add(winner);
            
            if (!activeSessions.isEmpty()) {
                Bukkit.broadcastMessage("§a[배틀 종료] §f" + winner.getName() + "님 승리! §7(대기합니다)");
                winner.setGameMode(GameMode.SPECTATOR);
                teleportToSpectate(winner);
                if (loser != null && loser.isOnline()) {
                    loser.setGameMode(GameMode.SPECTATOR);
                    teleportToSpectate(loser);
                }
            } else {
                Bukkit.broadcastMessage("§a[배틀 종료] §f" + winner.getName() + "님 승리!");
                if (loser != null && loser.isOnline()) {
                    loser.setGameMode(GameMode.SPECTATOR);
                }
            }
        }

        if (activeSessions.isEmpty()) {
            alivePlayers = new ArrayList<>(nextRoundPlayers);
            new BukkitRunnable() {
                @Override
                public void run() {
                    startNextRound();
                }
            }.runTaskLater(plugin, 100L); 
        }
    }

    private void createAndDistributeBooks(BattleSession session, JsonObject storyJson, Player winner) {
        ItemStack combinedBook = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) combinedBook.getItemMeta();
        
        String title = storyJson.has("title") ? storyJson.get("title").getAsString() : "전투의 기록";
        String story = storyJson.has("story") ? storyJson.get("story").getAsString() : "기록이 소실되었습니다.";
        
        meta.setTitle("§d" + title);
        meta.setAuthor(winner != null ? winner.getName() : "작자 미상");

        StringBuilder pageBuilder = new StringBuilder();
        
        int charCount = 0;
        String[] words = story.split(" ");
        for (String word : words) {
            if (charCount + word.length() > 200) {
                meta.addPage(pageBuilder.toString());
                pageBuilder = new StringBuilder();
                charCount = 0;
            }
            pageBuilder.append(word).append(" ");
            charCount += word.length() + 1;
            
            if (word.contains("\n")) {
                charCount += 18; 
            }
        }
        if (pageBuilder.length() > 0) {
            meta.addPage(pageBuilder.toString());
            pageBuilder = new StringBuilder(); 
        }

        meta.addPage("§8\n\n\n\n\n     [ 부록: 잊혀진 기록 ]\n\n\n§0이하 내용은 전장에서 기록된\n사실만을 나열한 것입니다.");

        int lineCount = 0;
        for (String line : session.getMatchLogs()) {
            int estimatedLines = (line.length() / 18) + 1;
            if (pageBuilder.length() + line.length() > 220 || lineCount + estimatedLines > 12) {
                meta.addPage(pageBuilder.toString());
                pageBuilder = new StringBuilder();
                lineCount = 0;
            }
            pageBuilder.append(line).append("\n");
            lineCount += estimatedLines;
        }
        if (pageBuilder.length() > 0) {
            meta.addPage(pageBuilder.toString());
        }

        combinedBook.setItemMeta(meta);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getInventory().firstEmpty() != -1) {
                p.getInventory().addItem(combinedBook);
            } else {
                p.getWorld().dropItem(p.getLocation(), combinedBook); 
            }
            p.sendMessage("§d[기록 보관소] §f방금 끝난 전투의 영웅담이 책으로 발간되었습니다!");
        }
    }

    public BattleSession getSessionByPlayer(Player player) {
        for (BattleSession session : activeSessions) {
            if (session.hasPlayer(player)) return session;
        }
        return null;
    }
    
    public void sendMessageToSessionGroup(BattleSession session, String message) {
        if (session.getPlayer1().isOnline()) session.getPlayer1().sendMessage(message);
        if (session.getPlayer2().isOnline()) session.getPlayer2().sendMessage(message);
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                BattleSession closestSession = null;
                double minDistance = Double.MAX_VALUE;
                
                for (BattleSession active : activeSessions) {
                    Player p1 = active.getPlayer1();
                    if (p1 != null && p1.isOnline() && p1.getWorld().equals(p.getWorld())) {
                        double dist = p.getLocation().distanceSquared(p1.getLocation());
                        if (dist < minDistance) {
                            minDistance = dist;
                            closestSession = active;
                        }
                    }
                }
                
                if (closestSession == session) {
                    p.sendMessage(message);
                }
            }
        }
    }
    
    public BattleSession getSessionBySpectator(Player spectator) {
        BattleSession closestSession = null;
        double minDistance = Double.MAX_VALUE;
        
        for (BattleSession session : activeSessions) {
            Player p1 = session.getPlayer1();
            if (p1 != null && p1.isOnline() && p1.getWorld().equals(spectator.getWorld())) {
                double dist = spectator.getLocation().distanceSquared(p1.getLocation());
                if (dist < minDistance) {
                    minDistance = dist;
                    closestSession = session;
                }
            }
        }
        return closestSession;
    }
    public boolean isGameRunning() { return isGameRunning; }
    public boolean isTournamentPhase() { return isTournamentPhase; }
}