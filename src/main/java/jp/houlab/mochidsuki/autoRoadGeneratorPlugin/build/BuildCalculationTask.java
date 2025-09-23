package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.RoadPreset;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.BlockRotationUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class BuildCalculationTask extends BukkitRunnable {

    // 順序保持のための結果クラス
    private static class IndexedBlockResult {
        final int zIndex;
        final int jIndex;
        final List<BlockPlacementInfo> worldBlocks;
        final List<BlockPlacementInfo> originalBlocks;

        IndexedBlockResult(int zIndex, int jIndex, List<BlockPlacementInfo> worldBlocks, List<BlockPlacementInfo> originalBlocks) {
            this.zIndex = zIndex;
            this.jIndex = jIndex;
            this.worldBlocks = worldBlocks;
            this.originalBlocks = originalBlocks;
        }
    }

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;
    private final boolean onlyAir;

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset, boolean onlyAir) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.roadPreset = roadPreset;
        this.onlyAir = onlyAir;
    }

    // 既存のコンストラクタとの互換性を保持
    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset) {
        this(plugin, playerUUID, routeSession, roadPreset, false);
    }

    @Override
    public void run() {
        List<Location> path = routeSession.getCalculatedPath();
        if (path == null || path.isEmpty()) {
            return;
        }

        List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
        List<BlockPlacementInfo> originalBlocks = new ArrayList<>();

        // 計算開始をプレイヤーに通知
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "道路計算中... 経路長: " + path.size() + "ブロック");
            }
        });

        // 並列処理用のスレッドプールを作成
        int numThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        // 進行状況追跡用変数
        int totalTasks = (roadPreset.getWidthZ()/2+2) * 2;
        AtomicInteger completedTasks = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        AtomicReference<Long> lastReportTime = new AtomicReference<>(startTime);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "並列計算開始... CPU使用: " + numThreads + "スレッド");
            }
        });

        // 並列処理のFutureリスト
        List<Future<IndexedBlockResult>> futures = new ArrayList<>();

        // 道路建築アルゴリズム実装済み - 並列処理による高速化

        // Z軸方向の各セクションを並列処理で実行
        for (int zz = 0; zz <= roadPreset.getWidthZ()/2+1; zz++) {
            for(int j = 0; j < 2; j++) {
                final int finalZz = zz;
                final int finalJ = j;

                // 並列処理用のタスクを作成
                Future<IndexedBlockResult> future = executor.submit(() -> {
                    return calculateBlocksForZSection(finalZz, finalJ, path, roadPreset, completedTasks, totalTasks, startTime, lastReportTime);
                });

                futures.add(future);
            }
        }

        // 並列処理の結果を収集し、元の順序通りに結合
        List<IndexedBlockResult> results = new ArrayList<>();
        try {
            for (Future<IndexedBlockResult> future : futures) {
                results.add(future.get());
            }
        } catch (InterruptedException | ExecutionException e) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    player.sendMessage(ChatColor.RED + "並列計算中にエラーが発生しました: " + e.getMessage());
                }
            });
            executor.shutdown();
            return;
        }

        executor.shutdown();

        // 結果を元の順序通りにソートしてマージ
        results.sort((a, b) -> {
            int zzCompare = Integer.compare(a.zIndex, b.zIndex);
            if (zzCompare != 0) return zzCompare;
            return Integer.compare(a.jIndex, b.jIndex);
        });

        // 最終的なブロックリストに順序通りに追加
        for (IndexedBlockResult result : results) {
            worldBlocks.addAll(result.worldBlocks);
            originalBlocks.addAll(result.originalBlocks);
        }

        // 計算完了をプレイヤーに通知
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                String modeText = onlyAir ? " (空気ブロックのみ設置)" : "";
                player.sendMessage(ChatColor.GREEN + "計算完了! " + worldBlocks.size() + "ブロックの設置を開始します" + modeText);
            }
        });

        BuildHistoryManager.addBuildHistory(playerUUID,originalBlocks);
        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(worldBlocks);
        new BuildPlacementTask(plugin, playerUUID, placementQueue, onlyAir).runTaskTimer(plugin,1,1);
    }

    /**
     * Z軸の特定セクションを並列処理で計算するメソッド
     */
    private IndexedBlockResult calculateBlocksForZSection(int zz, int j, List<Location> path, RoadPreset roadPreset,
                                                         AtomicInteger completedTasks, int totalTasks, long startTime, AtomicReference<Long> lastReportTime) {
        List<BlockPlacementInfo> sectionWorldBlocks = new ArrayList<>();
        List<BlockPlacementInfo> sectionOriginalBlocks = new ArrayList<>();

        int z;
        if(j < 1){
            z = roadPreset.getMaxZ() - zz;
        } else {
            z = roadPreset.getMinZ() + zz;
        }

        float x = 0;
        for (Location pathPoint : path) {
            float yaw = pathPoint.getYaw();   // 進行方向のヨー角（度）
            float pitch = pathPoint.getPitch(); // 進行方向のピッチ角（度）
            float rightYaw = yaw + 90f;       // 右方向のヨー角（+90度回転）

            // 進行方向ベクトル（ピッチを考慮）
            double forwardX = Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
            double forwardY = -Math.sin(Math.toRadians(pitch));
            double forwardZ = Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
            Vector forwardVector = new Vector(forwardX, forwardY, forwardZ).normalize();

            // 右方向ベクトル（水平面での法線）
            double rightX = Math.cos(Math.toRadians(rightYaw));
            double rightZ = Math.sin(Math.toRadians(rightYaw));
            Vector rightVector = new Vector(rightX, 0, rightZ).normalize();

            // 上方向ベクトル（進行方向と右方向の外積で計算）
            Vector upVector = rightVector.clone().crossProduct(forwardVector).normalize();

            RoadPreset.PresetSlice slice = roadPreset.getSlices().get((int) x);
            Location location = pathPoint.clone().add(rightVector.clone().multiply(z));

            out:
            for (int y = roadPreset.getMinY(); y <= roadPreset.getMaxY()+1; y++) {

                BlockData blockData = slice.getBlockRelativeToAxis(z, y, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());

                if (blockData != null) {

                    Location worldLocation = location.clone().add(0,y,0);

                    // BlockDataのクローンを作成して参照共有を防ぐ
                    BlockData clonedBlockData = blockData.clone();

                    // パスポイントの向きに合わせてBlockDataを回転（Facingも一緒に回転）
                    clonedBlockData = BlockRotationUtil.rotateBlockData(clonedBlockData, Math.toRadians(yaw));

                    // 坂を滑らかにするために、ハーフブロックの高さを調整
                    if(clonedBlockData instanceof Slab){
                        Slab slab = (Slab) clonedBlockData;

                        // 上のブロックが空気かどうかをチェック
                        BlockData blockAbove = null;
                        if (y + 1 <= roadPreset.getMaxY()) {
                            blockAbove = slice.getBlockRelativeToAxis(z, y + 1, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());
                        }

                        // ハーフブロックで上が空気の場合、地形に合わせて調整
                        if (blockAbove == null || blockAbove.getMaterial() == Material.AIR) { // 上が空気または範囲外

                            // 地面の高さと比較してスラブタイプを決定
                            int groundY = (int) Math.floor(worldLocation.getY());
                            double heightAboveGround = worldLocation.getY() - groundY;

                            // 地面から0.5ブロック以下の場合はBOTTOMスラブ、それ以外はDOUBLE

                            if(((Slab)blockData).getType() == Slab.Type.BOTTOM) {
                                if (heightAboveGround < 0.5) {
                                    break out;
                                }else {
                                    slab.setType(Slab.Type.BOTTOM);
                                }
                            }else {
                                if (heightAboveGround < 0.5) {
                                    slab.setType(Slab.Type.BOTTOM);
                                } else {
                                    slab.setType(Slab.Type.DOUBLE);
                                }
                            }

                        } else {
                            // 上にブロックがある場合は通常のダブルスラブ
                            slab.setType(Slab.Type.DOUBLE);
                        }
                    }

                    sectionOriginalBlocks.add(new BlockPlacementInfo(worldLocation,worldLocation.getBlock().getBlockData()));
                    sectionWorldBlocks.add(new BlockPlacementInfo(worldLocation, clonedBlockData));
                }
            }

            x += 0.5f;
            if (x >= roadPreset.getLengthX()) x = 0;
        }

        // 進行状況を更新 (10秒間隔 + ETA表示)
        int completed = completedTasks.incrementAndGet();
        long currentTime = System.currentTimeMillis();
        long lastReport = lastReportTime.get();

        // 10秒経過した場合のみ報告
        if (currentTime - lastReport >= 10000) { // 10秒 = 10000ms
            if (lastReportTime.compareAndSet(lastReport, currentTime)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player != null && player.isOnline()) {
                        int currentPercent = (int) ((double) completed / totalTasks * 100);
                        long elapsedTime = currentTime - startTime;

                        // ETA計算: 残り時間 = (経過時間 * 残りタスク) / 完了タスク
                        String etaText = "";
                        if (completed > 0) {
                            long estimatedTotalTime = (elapsedTime * totalTasks) / completed;
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

                        player.sendMessage(ChatColor.GREEN + "並列計算進行: " + currentPercent + "% (" + completed + "/" + totalTasks + " セクション完了)" + etaText);
                    }
                });
            }
        }

        return new IndexedBlockResult(zz, j, sectionWorldBlocks, sectionOriginalBlocks);
    }

}