package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Queue;
import java.util.UUID;

public class BuildPlacementTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final Queue<BlockPlacementInfo> placementQueue;
    private final int blocksPerTick = 500; // 1ティックあたりの設置ブロック数
    private int totalBlocksPlaced = 0;
    private final int totalBlocksToPlace;

    public BuildPlacementTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, Queue<BlockPlacementInfo> placementQueue) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.placementQueue = placementQueue;
        this.totalBlocksToPlace = placementQueue.size();
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
                    blockLocation.getBlock().setBlockData(info.data(), false); // falseで物理的な更新を抑制
                    placedThisTick++;
                    totalBlocksPlaced++;
                }
            }
        }

        // 進捗状況をプレイヤーに報告 (任意)
        if (totalBlocksToPlace > 0 && totalBlocksPlaced % (blocksPerTick * 10) == 0) { // 10ティックごとに報告
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (Bukkit.getPlayer(playerUUID) != null) {
                    double progress = (double) totalBlocksPlaced / totalBlocksToPlace * 100;
                    Bukkit.getPlayer(playerUUID).sendMessage(ChatColor.YELLOW + String.format("道路建築中: %.1f%% 完了 (%d / %d ブロック)", progress, totalBlocksPlaced, totalBlocksToPlace));
                }
            });
        }
    }
}
