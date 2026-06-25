package heipsys;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BattleSession {

    private final Plugin plugin;
    private final AIBattleManager aiManager;
    private final GameManager gameManager;
    
    private final Player player1;
    private final Player player2;
    
    private int round = 1;
    private Player currentSpeaker;
    private String p1Action = null;
    private String p2Action = null;

    // 🌟 Key-Value 형태의 상태 관리를 위해 Map (순서 보장을 위해 LinkedHashMap) 사용
    private final Map<String, String> p1StatusMap = new LinkedHashMap<>();
    private final Map<String, String> p2StatusMap = new LinkedHashMap<>();
    
    private boolean isSessionActive = true;
    private boolean isIntroGenerating = true; 
    private boolean isEnding = false;
    private final String biomeName;
    
    // 현재 재생 중인 BGM을 추적하기 위한 변수
    private String currentBgm = "";
    
    private final List<JsonObject> chatHistory = new ArrayList<>();
    private final List<String> matchLogs = new ArrayList<>(); 

    private BukkitTask turnTimer = null;
    private int timeLeft = 0;
    private int nextTurnTime = 40; 
    
    public BattleSession(Plugin plugin, AIBattleManager aiManager, GameManager gameManager, Player player1, Player player2, String biomeName) {
        this.biomeName = biomeName;
        this.plugin = plugin;
        this.aiManager = aiManager;
        this.gameManager = gameManager;
        this.player1 = player1;
        this.player2 = player2;
        
        chatHistory.clear(); 
        prepareBattleIntro(); 
    }

    public boolean hasPlayer(Player player) {
        return player.equals(player1) || player.equals(player2);
    }

    public Player getPlayer1() { return player1; }
    public Player getPlayer2() { return player2; }
    public List<String> getMatchLogs() { return matchLogs; } 

    private List<Player> getSessionPlayers() {
        List<Player> players = new ArrayList<>();
        if (player1 != null && player1.isOnline()) players.add(player1);
        if (player2 != null && player2.isOnline()) players.add(player2);
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                if (gameManager.getSessionBySpectator(p) == this) {
                    players.add(p);
                }
            }
        }
        return players;
    }

    private void prepareBattleIntro() {
        if (!isSessionActive) return;
        if (checkDeath()) return;

        broadcast("§e============== [ " + player1.getName() + " vs " + player2.getName() + " ] ==============");
        
        String p1Background = gameManager.getLegacy(player1);
        String p2Background = gameManager.getLegacy(player2);

        boolean hasP1Legacy = p1Background != null && !p1Background.equals("없음") && !p1Background.trim().isEmpty();
        boolean hasP2Legacy = p2Background != null && !p2Background.equals("없음") && !p2Background.trim().isEmpty();

        if (hasP1Legacy || hasP2Legacy) {
            broadcast("§d[영웅의 서사] §f과거의 기록이 전장에 울려 퍼집니다...");
            matchLogs.add("§d[영웅의 서사]");
            if (hasP1Legacy) {
                String msg = "§9[" + player1.getName() + "의 서사] §7" + p1Background;
                broadcast(msg);
                matchLogs.add(msg);
            }
            if (hasP2Legacy) {
                String msg = "§c[" + player2.getName() + "의 서사] §7" + p2Background;
                broadcast(msg);
                matchLogs.add(msg);
            }
            broadcast(""); 
        }

        broadcast("§8[시스템] 두 플레이어의 조우를 묘사 중입니다...");
     // 👇 [추가됨] config에서 현재 로직 레벨을 가져옴
        int logicLevel = plugin.getConfig().getInt("logic-level", 2);

        aiManager.generateBattleIntroAsync(
                player1.getName(), p1Background, 
                player2.getName(), p2Background, 
                biomeName, logicLevel // 👇 [추가됨] 마지막 파라미터로 logicLevel 전달
        ).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isSessionActive) return;

                String intro = result.has("intro_narrative") ? result.get("intro_narrative").getAsString() : "결전을 앞둔 두 영웅이 전장에 섰습니다.";
                
                if (result.has("bgm") && !result.get("bgm").isJsonNull()) {
                    String startBgm = result.get("bgm").getAsString().trim().toUpperCase();
                    if (!startBgm.isEmpty() && !startBgm.equals("NONE")) {
                        changeBgm(startBgm);
                    }
                }
                
                broadcast("\n§6[전장 조우] §f" + intro + "\n");
                
                matchLogs.add("§6[시작] §0" + intro);
                JsonObject aiLog = new JsonObject();
                aiLog.addProperty("role", "system");
                aiLog.addProperty("content", "[시작 배경] " + intro);
                chatHistory.add(aiLog);

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!isSessionActive) return;
                    isIntroGenerating = false;
                    startRound(); 
                }, 200L);
            });
        });
    }

    private void startRound() {
        if (!isSessionActive) return;
        if (checkDeath()) return;

        p1Action = null;
        p2Action = null;
        currentSpeaker = (round % 2 != 0) ? player1 : player2;

        broadcast("§b[라운드 " + round + "] §f선언 순서: §a" + currentSpeaker.getName() + " §7-> §c" + getOtherPlayer(currentSpeaker).getName());
        matchLogs.add("§8[ 라운드 " + round + " ]"); 
        
        if (currentSpeaker.isOnline()) {
            broadcast("§a[!] §f" + currentSpeaker.getName() + "님의 차례입니다. 행동을 입력하세요! §e(제한시간: " + nextTurnTime + " 초)");
            startTurnTimer(currentSpeaker, nextTurnTime);
        }
    }

    public void onPlayerActionDeclare(Player player, String actionChat) {
        if (isIntroGenerating) {
            player.sendMessage("§c현재 전장 상황을 묘사 중입니다. 잠시만 기다려주세요!");
            return;
        }
        
        if (!isSessionActive || player != currentSpeaker) {
            player.sendMessage("§c지금은 당신이 말할 차례가 아닙니다!");
            return;
        }

        stopTurnTimer();

        if (player == player1) p1Action = actionChat;
        else p2Action = actionChat;

        broadcast("§6[" + player.getName() + "의 선언] §f" + actionChat);
        
        String color = (player == player1) ? "§9" : "§c";
        matchLogs.add(color + "[" + player.getName() + "] §0" + actionChat);
        
        JsonObject log = new JsonObject();
        log.addProperty("role", "user");
        log.addProperty("content", player.getName() + " 행동: " + actionChat);
        chatHistory.add(log);
        
        if (p1Action != null && p2Action != null) {
            currentSpeaker = null;
            resolveClash();
        } else {
            currentSpeaker = getOtherPlayer(player);
            if (currentSpeaker.isOnline()) {
                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
                    broadcast("§a[!] §f" + currentSpeaker.getName() + "님의 차례입니다. 대응 행동을 입력하세요! §e(제한시간: " + nextTurnTime + " 초)");
                    startTurnTimer(currentSpeaker, nextTurnTime);
                }, 20L); 
            } else {
                checkDeath();
            }
        }
    }

    private void startTurnTimer(Player player, int limitSeconds) {
        stopTurnTimer();
        timeLeft = limitSeconds;
        int totalTime = limitSeconds;

        turnTimer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!isSessionActive || currentSpeaker != player || isEnding) {
                stopTurnTimer();
                return;
            }

            float expProgress = Math.max(0f, Math.min(1f, (float) timeLeft / totalTime));
            List<Player> sessionPlayers = getSessionPlayers();
            
            for (Player p : sessionPlayers) {
                p.setLevel(timeLeft);
                p.setExp(expProgress);
            }

            if (timeLeft == 10 || (timeLeft <= 5 && timeLeft > 0)) {
                broadcast("§c[경고] §f" + player.getName() + "님의 턴 종료까지 §e" + timeLeft + "초 §f남았습니다!");
                for (Player p : sessionPlayers) {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 1.0f);
                }
            }

            if (timeLeft <= 0) {
                stopTurnTimer();
                broadcast("§c[시간 초과] §f" + player.getName() + "님의 시간이 초과되어 강제로 턴을 넘깁니다!");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    onPlayerActionDeclare(player, "시간 초과로 인해 턴 허비");
                });
            }
            timeLeft--;
        }, 0L, 20L); 
    }

    private void stopTurnTimer() {
        if (turnTimer != null) {
            turnTimer.cancel();
            turnTimer = null;
        }
        for (Player p : getSessionPlayers()) {
            p.setLevel(0);
            p.setExp(0f);
        }
    }

    private void resolveClash() {
        broadcast("§8[시스템] AI 게임 마스터가 상황을 판정 중입니다...");

        String status1 = getPlayerStatusString(player1);
        String status2 = getPlayerStatusString(player2);
        String p1Legacy = gameManager.getLegacy(player1);
        String p2Legacy = gameManager.getLegacy(player2);

        // Map에 담긴 상태들을 텍스트 포맷으로 변환하여 AI에게 전송
        String p1EffectsStr = formatStatusMapForAI(p1StatusMap);
        String p2EffectsStr = formatStatusMapForAI(p2StatusMap);

        int logicLevel = plugin.getConfig().getInt("logic-level", 2);
        boolean rpMode = plugin.getConfig().getBoolean("rp-mode", false);

        aiManager.evaluateClashAsync(
            chatHistory,
            player1.getName(), p1Legacy, status1, p1Action, p1EffectsStr,
            player2.getName(), p2Legacy, status2, p2Action, p2EffectsStr, biomeName,
            logicLevel, rpMode
        ).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> processAiResult(result));
        });
    }

    private void processAiResult(JsonObject result) {
        if (!isSessionActive) return;

        String narrative = result.has("narrative") ? result.get("narrative").getAsString() : "결전을 알 수 없는 공방입니다.";
        broadcast("\n§e[게임 마스터] §f" + narrative);
        
        matchLogs.add("§5[AI] §0" + narrative);

        if (result.has("sound_effect") && !result.get("sound_effect").isJsonNull()) {
            playSoundEffect(result.get("sound_effect").getAsString());
        }

        if (result.has("bgm") && !result.get("bgm").isJsonNull()) {
            String aiBgm = result.get("bgm").getAsString().trim().toUpperCase();
            if (!aiBgm.isEmpty() && !aiBgm.equals("NONE") && !aiBgm.equals(currentBgm)) {
                changeBgm(aiBgm);
            }
        }
        
        if (result.has("next_turn_limit") && !result.get("next_turn_limit").isJsonNull()) {
            try {
                int aiLimit = result.get("next_turn_limit").getAsInt();
                nextTurnTime = Math.max(3, Math.min(120, aiLimit));
            } catch (Exception e) {
                nextTurnTime = 30;
            }
        } else {
            nextTurnTime = 30;
        }

        JsonObject p1Res = result.has("p1_result") && result.get("p1_result").isJsonObject() ? result.getAsJsonObject("p1_result") : new JsonObject();
        JsonObject p2Res = result.has("p2_result") && result.get("p2_result").isJsonObject() ? result.getAsJsonObject("p2_result") : new JsonObject();

        giveResultItems(player1, p1Res);
        giveResultItems(player2, p2Res);
        consumeResultItems(player1, p1Res);
        consumeResultItems(player2, p2Res);
        applyHeal(player1, p1Res);
        applyHeal(player2, p2Res);
        applyDamage(player1, player2, p1Res);
        applyDamage(player2, player1, p2Res);

        // 🌟 Map 기반의 상태 업데이트 처리
        updateStatusMap(p1StatusMap, p1Res);
        updateStatusMap(p2StatusMap, p2Res);

        if (result.has("battle_summary")) {
            JsonObject summary = result.getAsJsonObject("battle_summary");
            if (summary.has("p1_death_reason") && !summary.get("p1_death_reason").isJsonNull()) {
                if (player1.isOnline() && player1.getHealth() > 0) player1.setHealth(0);
            }
            if (summary.has("p2_death_reason") && !summary.get("p2_death_reason").isJsonNull()) {
                if (player2.isOnline() && player2.getHealth() > 0) player2.setHealth(0);
            }
        }
        
        String p1Hp = String.format("%.1f", player1.getHealth());
        String p2Hp = String.format("%.1f", player2.getHealth());
        
        // 채팅창 출력용 텍스트 변환
        String p1EffectsDisplay = p1StatusMap.isEmpty() ? "§8없음" : "§e" + formatStatusMapForAI(p1StatusMap);
        String p2EffectsDisplay = p2StatusMap.isEmpty() ? "§8없음" : "§e" + formatStatusMapForAI(p2StatusMap);
        
        String p1StatusMsg = "§a" + player1.getName() + " §f[체력: §c" + p1Hp + "§f] §7| 상태: " + p1EffectsDisplay;
        String p2StatusMsg = "§c" + player2.getName() + " §f[체력: §c" + p2Hp + "§f] §7| 상태: " + p2EffectsDisplay;

        broadcast("§8-----------------------------------------");
        broadcast(p1StatusMsg);
        broadcast(p2StatusMsg);
        broadcast("§8-----------------------------------------\n");
        
        if (result.has("battle_summary")) {
            JsonObject summary = result.getAsJsonObject("battle_summary");
            if (player1.isOnline() && player1.getHealth() > 0 && summary.has("p1_legacy") && !summary.get("p1_legacy").isJsonNull()) {
                gameManager.saveLegacy(player1, summary.get("p1_legacy").getAsString());
            }
            if (player2.isOnline() && player2.getHealth() > 0 && summary.has("p2_legacy") && !summary.get("p2_legacy").isJsonNull()) {
                gameManager.saveLegacy(player2, summary.get("p2_legacy").getAsString());
            }
        }
        
        JsonObject aiLog = new JsonObject();
        aiLog.addProperty("role", "assistant");
        aiLog.addProperty("content", "게임 마스터: " + narrative);
        chatHistory.add(aiLog); 
        
        if (checkDeath(result)) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isSessionActive) return;
            round++;
            startRound();
        }, 100L);
    }

    // 🌟 Key-Value 기반 상태 갱신 로직 (완벽한 중복 방지 및 갱신)
    private void updateStatusMap(Map<String, String> statusMap, JsonObject res) {
        // 1. 상태 제거 (키 기반 매칭)
        if (res.has("remove_status") && res.get("remove_status").isJsonArray()) {
            for (JsonElement elem : res.get("remove_status").getAsJsonArray()) {
                String toRemove = extractStringSafely(elem).replaceAll("[\\[\\]]", "").trim().toLowerCase();
                if (!toRemove.isEmpty()) {
                    // 키워드가 맵의 Key에 포함되어 있다면 제거
                    statusMap.keySet().removeIf(key -> key.toLowerCase().contains(toRemove) || toRemove.contains(key.toLowerCase()));
                }
            }
        }
        
        // 2. 상태 추가 및 갱신 (키-값 파싱)
        if (res.has("add_status") && res.get("add_status").isJsonArray()) {
            for (JsonElement elem : res.get("add_status").getAsJsonArray()) {
                String raw = extractStringSafely(elem).trim();
                if (raw.isEmpty()) continue;

                String key = raw;
                String desc = "";

                // "[키워드] : 설명" 포맷 파싱
                int bracketStart = raw.indexOf("[");
                int bracketEnd = raw.indexOf("]");

                if (bracketStart != -1 && bracketEnd > bracketStart) {
                    key = raw.substring(bracketStart + 1, bracketEnd).trim(); // 대괄호 안의 문자열 추출
                    // 대괄호 뒤의 콜론(:)부터 끝까지 설명으로 추출
                    String remainder = raw.substring(bracketEnd + 1).trim();
                    if (remainder.startsWith(":")) {
                        desc = remainder.substring(1).trim();
                    } else {
                        desc = remainder;
                    }
                } else {
                    // 대괄호가 없는 경우 콜론(:)을 기준으로 스플릿 시도
                    String[] parts = raw.split(":", 2);
                    if (parts.length == 2) {
                        key = parts[0].trim();
                        desc = parts[1].trim();
                    }
                }
                
                // Map의 특성상 동일한 key(키워드)가 들어오면 기존 데이터를 자동으로 '덮어쓰기' 합니다.
                statusMap.put(key, desc);
            }
        }
    }

    // Map 데이터를 AI가 읽기 편하게 "[키워드] : 설명" 형태로 변환하는 유틸 메소드
    private String formatStatusMapForAI(Map<String, String> map) {
        if (map.isEmpty()) return "";
        List<String> list = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                list.add("[" + entry.getKey() + "]");
            } else {
                list.add("[" + entry.getKey() + "] : " + entry.getValue());
            }
        }
        return String.join(" | ", list);
    }

    private String extractStringSafely(JsonElement elem) {
        if (elem.isJsonPrimitive()) {
            return elem.getAsString();
        } else if (elem.isJsonObject()) {
            JsonObject obj = elem.getAsJsonObject();
            if (obj.has("name")) return obj.get("name").getAsString();
            if (obj.has("status")) return obj.get("status").getAsString();
        }
        return "";
    }
    
    private void playSoundEffect(String soundName) {
        if (soundName == null || soundName.trim().isEmpty() || soundName.equalsIgnoreCase("NONE")) return;
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            for (Player p : getSessionPlayers()) {
                p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            }
        } catch (IllegalArgumentException ignore) {}
    }

    private void changeBgm(String newBgm) {
        List<Player> sessionPlayers = getSessionPlayers();
        for (Player p : sessionPlayers) {
            p.stopSound(org.bukkit.SoundCategory.MUSIC);
            p.stopSound(org.bukkit.SoundCategory.RECORDS);
            p.stopSound(org.bukkit.SoundCategory.VOICE);
        }
        try {
            Sound sound = Sound.valueOf(newBgm);
            for (Player p : sessionPlayers) {
                p.playSound(p.getLocation(), sound, org.bukkit.SoundCategory.VOICE, 10000.0f, 1.0f);
            }
            this.currentBgm = newBgm; 
        } catch (IllegalArgumentException ignore) { }
    }

    private void giveResultItems(Player player, JsonObject res) {
        if (!player.isOnline()) return;
        if (res.has("give_items") && res.get("give_items").isJsonArray()) {
            for (JsonElement elem : res.get("give_items").getAsJsonArray()) {
                String originalMatName = "";
                String customName = null;

                if (elem.isJsonObject()) {
                    JsonObject itemObj = elem.getAsJsonObject();
                    originalMatName = itemObj.has("material") ? itemObj.get("material").getAsString() : "PAPER";
                    if (itemObj.has("name") && !itemObj.get("name").isJsonNull()) {
                        customName = itemObj.get("name").getAsString();
                    }
                } else if (elem.isJsonPrimitive()) {
                    originalMatName = elem.getAsString();
                }

                String formattedMatName = originalMatName.trim().toUpperCase().replace(" ", "_");
                Material mat;

                try {
                    mat = Material.valueOf(formattedMatName);
                } catch (IllegalArgumentException e) {
                    mat = Material.PAPER;
                    if (customName == null || customName.trim().isEmpty()) {
                        customName = originalMatName;
                    }
                }

                ItemStack item = new ItemStack(mat, 1);
                
                if (customName != null && !customName.trim().isEmpty()) {
                    org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName("§d[AI 하사품] §f" + customName);
                        meta.setLore(java.util.Arrays.asList(
                            "§7게임 마스터가 특별히 하사한 아이템입니다.",
                            "§8(형태는 " + mat.name() + "이지만, 강력한 힘을 가졌습니다.)"
                        ));
                        item.setItemMeta(meta);
                    }
                }

                String display = (customName != null && !customName.trim().isEmpty()) ? customName : mat.name();
                if (player.getInventory().firstEmpty() != -1) {
                    player.getInventory().addItem(item);
                    broadcast("§b[아이템 지급] §f" + player.getName() + "님이 " + display + "을(를) 획득했습니다!");
                } else {
                    player.getWorld().dropItem(player.getLocation(), item);
                    broadcast("§e[아이템 지급] §f인벤토리가 가득 차 발밑에 " + display + "이 떨어졌습니다!");
                }
            }
        }
    }

    private void applyHeal(Player player, JsonObject res) {
        if (!player.isOnline()) return;
        if (res.has("heal_amount")) {
            int heal = res.get("heal_amount").getAsInt();
            if (heal > 0) {
                double newHealth = Math.min(player.getMaxHealth(), player.getHealth() + heal);
                player.setHealth(newHealth);
                player.sendMessage("§a[회복] §f" + heal + "의 체력을 회복했습니다.");
            }
        }
    }

    private void applyDamage(Player victim, Player attacker, JsonObject res) {
        if (!victim.isOnline()) return;
        if (res.has("damage_taken")) {
            int damage = res.get("damage_taken").getAsInt();
            if (damage > 0) {
                double newHealth = Math.max(0, victim.getHealth() - damage);
                victim.setHealth(newHealth);
                victim.playEffect(org.bukkit.EntityEffect.HURT);
            }
        }
    }

    private boolean checkDeath() {
        return checkDeath(null);
    }

    private boolean checkDeath(JsonObject aiResult) {
        if (isEnding) return true;

        boolean p1Offline = !player1.isOnline();
        boolean p2Offline = !player2.isOnline();
        
        boolean p1Dead = p1Offline || player1.isDead() || player1.getHealth() <= 0;
        boolean p2Dead = p2Offline || player2.isDead() || player2.getHealth() <= 0;

        if (!p1Dead && !p2Dead) return false;

        stopTurnTimer(); 
        isEnding = true;
        isSessionActive = false;

        for (Player p : getSessionPlayers()) {
            p.stopSound(org.bukkit.SoundCategory.MUSIC);
            p.stopSound(org.bukkit.SoundCategory.RECORDS);
        }

        if (aiResult != null && aiResult.has("battle_summary")) {
            JsonObject summary = aiResult.getAsJsonObject("battle_summary");
            StringBuilder endSummaryForAI = new StringBuilder("[전투 종료 사유] ");
            
            if (summary.has("special_win_reason") && !summary.get("special_win_reason").isJsonNull()) {
                String msg = "§6[특수 승리] §7" + summary.get("special_win_reason").getAsString();
                Bukkit.broadcastMessage(msg);
                matchLogs.add(msg);
                endSummaryForAI.append(msg).append(" ");
            }
            if (p1Dead && summary.has("p1_death_reason") && !summary.get("p1_death_reason").isJsonNull()) {
                String msg = "§c[사망 요약] §7" + player1.getName() + ": " + summary.get("p1_death_reason").getAsString();
                Bukkit.broadcastMessage(msg);
                matchLogs.add(msg);
                endSummaryForAI.append(msg).append(" ");
            }
            if (p2Dead && summary.has("p2_death_reason") && !summary.get("p2_death_reason").isJsonNull()) {
                String msg = "§c[사망 요약] §7" + player2.getName() + ": " + summary.get("p2_death_reason").getAsString();
                Bukkit.broadcastMessage(msg);
                matchLogs.add(msg);
                endSummaryForAI.append(msg).append(" ");
            }

            JsonObject endLog = new JsonObject();
            endLog.addProperty("role", "system");
            endLog.addProperty("content", endSummaryForAI.toString());
            chatHistory.add(endLog);
        }

        if (p1Offline) {
            Bukkit.broadcastMessage("§c[도주] §f" + player1.getName() + "님이 접속을 종료하여 탈락했습니다!");
            matchLogs.add("§c[도주] §f" + player1.getName() + " 접속 종료");
        }
        if (p2Offline) {
            Bukkit.broadcastMessage("§c[도주] §f" + player2.getName() + "님이 접속을 종료하여 탈락했습니다!");
            matchLogs.add("§c[도주] §f" + player2.getName() + " 접속 종료");
        }

        Player winner = null;
        Player loser = null;
        boolean draw = false;
        
        if (p1Dead && p2Dead) {
            draw = true;
        } else if (p1Dead) {
            winner = p2Offline ? null : player2;
            loser = player1;
        } else {
            winner = p1Offline ? null : player1;
            loser = player2;
        }

        String winnerName = draw ? "무승부" : (winner != null ? winner.getName() : "작자 미상");

        final Player fWinner = winner;
        final Player fLoser = loser;
        final boolean fDraw = draw;

        // config.yml에서 책 집필 여부 확인 (옵션이 없으면 기본값 true)
        boolean generateStory = plugin.getConfig().getBoolean("generate-epic-story", true);

        if (generateStory) {
            broadcast("§8[시스템] 서기(AI)가 방금 종료된 전투의 영웅기를 집필 중입니다...");
            
            aiManager.generateEpicStoryAsync(chatHistory, player1.getName(), player2.getName(), winnerName, matchLogs)
                .thenAccept(storyJson -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        gameManager.onBattleEnd(this, fWinner, fLoser, fDraw, storyJson);
                    });
                });
        } else {
            broadcast("§8[시스템] 서기(시스템)가 전투 로그를 기록 중입니다...");
            
            // 토큰 절약을 위해 AI를 호출하지 않고 자체적으로 JSON 객체를 조립
            JsonObject rawStoryJson = new JsonObject();
            
            String title = (draw ? "무승부의 기록: " : fWinner.getName() + "의 승리: ") + player1.getName() + " vs " + player2.getName();
            rawStoryJson.addProperty("title", title);
            
            // matchLogs 배열의 내용들을 보기 좋게 줄바꿈하여 하나의 문자열로 합침
            StringBuilder rawLogs = new StringBuilder();
            rawStoryJson.addProperty("story", rawLogs.toString());
            
            // 바로 GameManager로 넘김
            Bukkit.getScheduler().runTask(plugin, () -> {
                gameManager.onBattleEnd(this, fWinner, fLoser, fDraw, rawStoryJson);
            });
        }

        return true;
    }

    private Player getOtherPlayer(Player player) {
        return (player == player1) ? player2 : player1;
    }

    private void broadcast(String message) {
        gameManager.sendMessageToSessionGroup(this, message);
    }

    private String getPlayerStatusString(Player player) {
        if (!player.isOnline()) return "[접속 종료]";
        StringBuilder sb = new StringBuilder();
        PlayerInventory inv = player.getInventory();

        sb.append("[장착 중]\n");
        sb.append("- 주무기: ").append(getItemNameWithCustom(inv.getItemInMainHand())).append("\n");
        sb.append("- 보조무기: ").append(getItemNameWithCustom(inv.getItemInOffHand())).append("\n");
        
        sb.append("[소지품]\n");
        boolean hasItem = false;
        for (ItemStack item : inv.getStorageContents()) {
            if (item != null && !item.getType().isAir() && item.getType() != Material.WRITTEN_BOOK) {
                sb.append(getItemNameWithCustom(item)).append("(").append(item.getAmount()).append("개), ");
                hasItem = true;
            }
        }
        if (!hasItem) sb.append("없음");
        return sb.toString();
    }

    private String getItemNameWithCustom(ItemStack item) {
        if (item == null || item.getType().isAir()) return "없음";
        
        String baseName = item.getType().name();
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String rawName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
            
            if (rawName.startsWith("[AI 하사품] ")) {
                String customName = rawName.replace("[AI 하사품] ", "").trim();
                return baseName + "[하사품:" + customName + "]";
            } else {
                return baseName + "[" + rawName + "]";
            }
        }
        return baseName;
    }


    private void consumeResultItems(Player player, JsonObject res) {
        if (!player.isOnline()) return;
        if (!res.has("consume_items") || !res.get("consume_items").isJsonArray()) return;

        for (JsonElement elem : res.get("consume_items").getAsJsonArray()) {
            String targetName = "";
            if (elem.isJsonObject()) {
                JsonObject itemObj = elem.getAsJsonObject();
                targetName = itemObj.has("name") ? itemObj.get("name").getAsString() : 
                            (itemObj.has("material") ? itemObj.get("material").getAsString() : "");
            } else {
                targetName = elem.getAsString();
            }

            if (targetName.isEmpty()) continue;
            
            // 🌟 [추가됨] AI가 "ALL"을 보내면 인벤토리를 완전히 초기화합니다.
            if (targetName.equalsIgnoreCase("ALL") || targetName.equals("모든 아이템")) {
                player.getInventory().clear();
                return; // 전체 삭제이므로 여기서 메서드를 종료하는 것이 맞음
            }

            String searchName = targetName.trim().toUpperCase().replace(" ", "_");

            for (ItemStack item : player.getInventory().getContents()) {
                if (item == null || item.getType() == Material.AIR) continue;

                String matName = item.getType().name();
                String displayName = "";
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    displayName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName())
                            .replace("[AI 하사품] ", "").trim().toUpperCase().replace(" ", "_");
                }

                if (matName.equalsIgnoreCase(searchName) || displayName.equalsIgnoreCase(searchName)) {
                    
                    if (item.getAmount() > 1) {
                        item.setAmount(item.getAmount() - 1);
                    } else {
                        player.getInventory().remove(item);
                    }
                    // 🌟 [수정됨] return; 에서 break; 로 변경! 
                    // 그래야 다음 지울 아이템(JSON 배열의 다음 요소)으로 넘어갑니다.
                    break; 
                }
            }
        }
    }
}