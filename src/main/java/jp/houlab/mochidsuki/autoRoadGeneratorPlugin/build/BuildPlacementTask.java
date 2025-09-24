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
    private final boolean updateBlockData; // ブロック更新を行うかどうか
    private final int blocksPerTick = 500; // 1ティックあたりの設置ブロック数
    private int totalBlocksPlaced = 0;
    private final int totalBlocksToPlace;
    private long startTime = System.currentTimeMillis();
    private long lastReportTime = startTime;

    // メインコンストラクタ（すべてのオプション指定可能）
    public BuildPlacementTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, Queue<BlockPlacementInfo> placementQueue, boolean onlyAir, boolean updateBlockData) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.placementQueue = placementQueue;
        this.onlyAir = onlyAir;
        this.updateBlockData = updateBlockData;
        this.totalBlocksToPlace = placementQueue.size();
    }

    // 既存のコンストラクタとの互換性を保持（onlyAir指定）
    public BuildPlacementTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, Queue<BlockPlacementInfo> placementQueue, boolean onlyAir) {
        this(plugin, playerUUID, placementQueue, onlyAir, true); // デフォルトでブロック更新を有効
    }

    // 既存のコンストラクタとの互換性を保持（基本）
    public BuildPlacementTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, Queue<BlockPlacementInfo> placementQueue) {
        this(plugin, playerUUID, placementQueue, false, true); // デフォルトでブロック更新を有効
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
                        org.bukkit.block.data.BlockData blockDataToPlace = info.data();

                        // ブロックデータ更新がONの場合、接続情報を破棄し回転情報のみ維持
                        if (updateBlockData && isConnectableBlock(blockDataToPlace)) {
                            blockDataToPlace = removeConnectionData(blockDataToPlace);
                        }

                        blockLocation.getBlock().setBlockData(blockDataToPlace, updateBlockData);
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

    /**
     * 接続可能ブロック（フェンス、壁、ガラス板等）かどうかを判定
     */
    private boolean isConnectableBlock(org.bukkit.block.data.BlockData blockData) {
        return blockData instanceof org.bukkit.block.data.type.Fence ||
               blockData instanceof org.bukkit.block.data.type.Wall ||
               blockData instanceof org.bukkit.block.data.type.GlassPane ||
               blockData instanceof org.bukkit.block.data.type.Gate ||
               blockData instanceof org.bukkit.block.data.MultipleFacing;
    }

    /**
     * 接続情報を削除し、回転情報のみを維持する
     */
    private org.bukkit.block.data.BlockData removeConnectionData(org.bukkit.block.data.BlockData originalData) {
        // 元のブロックデータをクローン
        org.bukkit.block.data.BlockData cleanData = originalData.clone();

        try {
            // MultipleFacing（フェンス、ガラス板等）の接続情報をリセット
            if (cleanData instanceof org.bukkit.block.data.MultipleFacing) {
                org.bukkit.block.data.MultipleFacing facingData = (org.bukkit.block.data.MultipleFacing) cleanData;
                // すべての面の接続を無効化
                for (org.bukkit.block.BlockFace face : facingData.getAllowedFaces()) {
                    facingData.setFace(face, false);
                }
            }

            // Wall（石の壁等）の接続情報をリセット
            if (cleanData instanceof org.bukkit.block.data.type.Wall) {
                org.bukkit.block.data.type.Wall wallData = (org.bukkit.block.data.type.Wall) cleanData;
                // 4方向の水平接続をリセット
                wallData.setHeight(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.data.type.Wall.Height.NONE);
                wallData.setHeight(org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.data.type.Wall.Height.NONE);
                wallData.setHeight(org.bukkit.block.BlockFace.EAST, org.bukkit.block.data.type.Wall.Height.NONE);
                wallData.setHeight(org.bukkit.block.BlockFace.WEST, org.bukkit.block.data.type.Wall.Height.NONE);
            }

            return cleanData;
        } catch (Exception e) {
            // エラーが発生した場合は元のデータを返す
            plugin.getLogger().warning("接続データ削除中にエラー: " + e.getMessage());
            return originalData;
        }
    }
}
