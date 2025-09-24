package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Queue;
import java.util.UUID;

public class BuildPlacementTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final Queue<BlockPlacementInfo> placementQueue;
    private final boolean onlyAir;
    private final int blocksPerTick = 500; // 1ティックあたりの設置ブロック数
    private int totalBlocksPlaced = 0;
    private final int totalBlocksToPlace;
    private long startTime = System.currentTimeMillis();
    private long lastReportTime = startTime;

    public BuildPlacementTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, Queue<BlockPlacementInfo> placementQueue, boolean onlyAir) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.placementQueue = placementQueue;
        this.onlyAir = onlyAir;
        this.totalBlocksToPlace = placementQueue.size();
    }

    // 既存のコンストラクタとの互換性を保持
    public BuildPlacementTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, Queue<BlockPlacementInfo> placementQueue) {
        this(plugin, playerUUID, placementQueue, false);
    }

    @Override
    public void run() {
        if (placementQueue.isEmpty()) {
            // 全てのブロックの設置が完了
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (Bukkit.getPlayer(playerUUID) != null) {
                    Bukkit.getPlayer(playerUUID).sendMessage(ChatColor.GREEN + "道路の建築が完了しました！ (合計 " + totalBlocksPlaced + " ブロック)");
                }
            });
            this.cancel(); // タスクを自己終了
            return;
        }

        int placedThisTick = 0;
        while (placedThisTick < blocksPerTick && !placementQueue.isEmpty()) {
            BlockPlacementInfo info = placementQueue.poll();
            if (info != null) {
                // info.position() が既に Location なので、直接 getWorld() を呼び出せる
                World world = info.position().getWorld();
                if (world != null) {
                    // Location オブジェクトは既にワールド情報を含んでいるため、toLocation(world) は不要
                    Location blockLocation = info.position();

                    // onlyAirが有効な場合は空気ブロックのみに設置
                    if (!onlyAir || blockLocation.getBlock().getType() == Material.AIR) {
                        blockLocation.getBlock().setBlockData(info.data(), false); // falseで物理的な更新を抑制
                        placedThisTick++;
                        totalBlocksPlaced++;
                    } else {
                        // 空気ブロック以外はスキップしたがカウンターは進める
                        totalBlocksPlaced++;
                    }
                }
            }
        }

        // 進捗状況をプレイヤーに報告 (10秒間隔 + ETA表示)
        if (totalBlocksToPlace > 0) {
            long currentTime = System.currentTimeMillis();

            // 5秒経過した場合のみ報告（より頻繁に更新）
            if (currentTime - lastReportTime >= 5000) { // 5秒 = 5000ms
                lastReportTime = currentTime;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        int currentPercent = (int) ((double) totalBlocksPlaced / totalBlocksToPlace * 100);
                        long elapsedTime = currentTime - startTime;

                        // ETA計算: 残り時間 = (経過時間 * 残りブロック) / 設置済みブロック
                        String etaText = "";
                        if (totalBlocksPlaced > 0) {
                            long estimatedTotalTime = (elapsedTime * totalBlocksToPlace) / totalBlocksPlaced;
                            long remainingTime = estimatedTotalTime - elapsedTime;

                            if (remainingTime > 0) {
                                long remainingSeconds = remainingTime / 1000;
                                if (remainingSeconds < 60) {
                                    etaText = " ETA: " + remainingSeconds + "秒";
                                } else {
                                    long remainingMinutes = remainingSeconds / 60;
                                    long remainingSecondsRemainder = remainingSeconds % 60;
                                    etaText = " ETA: " + remainingMinutes + "分" + remainingSecondsRemainder + "秒";
                                }
                            } else {
                                etaText = " ETA: まもなく完了";
                            }
                        }

                        String modeText = onlyAir ? " (空気ブロックのみ)" : "";
                        player.sendMessage(ChatColor.AQUA + "設置進行: " + currentPercent + "% (" + totalBlocksPlaced + "/" + totalBlocksToPlace + ")" + etaText + modeText);
                    }
                });
            }
        }
    }
}
