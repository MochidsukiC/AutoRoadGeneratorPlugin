package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.RoadPreset;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.StringBlockRotationUtil;
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
    // グローバルロック削除 - String版プリセットで完全にスレッドセーフ

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
        List<Location> originalPath = routeSession.getCalculatedPath();
        if (originalPath == null || originalPath.isEmpty()) {
            return;
        }

        // スレッドセーフなコピーを作成
        List<Location> path = new ArrayList<>(originalPath);

        // 順序保持のための最終結果リスト
        List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
        List<BlockPlacementInfo> originalBlocks = new ArrayList<>();

        // 計算開始をプレイヤーに通知
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "道路計算中... 経路長: " + path.size() + "ブロック");
            }
        });

        // Work-Stealingスレッドプールを作成（負荷分散向上）
        int numThreads = Runtime.getRuntime().availableProcessors();
        ForkJoinPool executor = new ForkJoinPool(numThreads);

        // 細かい粒度でタスク分割（pathの分割も考慮）
        int pathChunkSize = Math.max(1, path.size() / (numThreads * 4)); // パスを細かく分割
        int zSections = (roadPreset.getWidthZ()/2+2) * 2;
        int pathChunks = (path.size() + pathChunkSize - 1) / pathChunkSize;
        int totalTasks = zSections * pathChunks;
        AtomicInteger completedTasks = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        AtomicReference<Long> lastReportTime = new AtomicReference<>(startTime);

        // 進行状況報告用のスケジューラタスク（安全な実装）
        BukkitRunnable progressReporter = new BukkitRunnable() {
            private volatile long lastReportedCompleted = 0;

            @Override
            public void run() {
                try {
                    int completed = completedTasks.get();
                    Player player = Bukkit.getPlayer(playerUUID);

                    if (player != null && player.isOnline()) {
                        int currentPercent = (int) ((double) completed / totalTasks * 100);
                        long elapsedTime = System.currentTimeMillis() - startTime;

                        // ETA計算（completed > 0 の場合のみ）
                        String etaText = "";
                        if (completed > 0) {
                            long estimatedTotalTime = (elapsedTime * totalTasks) / completed;
                            long remainingTime = estimatedTotalTime - elapsedTime;

                            if (remainingTime > 1000) { // 1秒以上の場合のみETA表示
                                long remainingSeconds = remainingTime / 1000;
                                if (remainingSeconds < 60) {
                                    etaText = " ETA: " + remainingSeconds + "秒";
                                } else {
                                    long remainingMinutes = remainingSeconds / 60;
                                    long remainingSecondsRemainder = remainingSeconds % 60;
                                    etaText = " ETA: " + remainingMinutes + "分" + remainingSecondsRemainder + "秒";
                                }
                            } else if (completed < totalTasks) {
                                etaText = " ETA: まもなく完了";
                            }
                        }

                        // 進捗が更新された場合のみメッセージを送信
                        if (completed != lastReportedCompleted) {
                            player.sendMessage(ChatColor.GREEN + "並列計算進行: " + currentPercent + "% (" + completed + "/" + totalTasks + " セクション完了)" + etaText);
                            lastReportedCompleted = completed;
                        }
                    }

                    // 計算完了時にタスクを停止
                    if (completed >= totalTasks) {
                        this.cancel();
                    }
                } catch (Exception e) {
                    // 例外をキャッチしてタスクが停止しないようにする
                    plugin.getLogger().warning("進行状況報告中にエラー: " + e.getMessage());
                }
            }
        };

        // 5秒後から5秒間隔で進行状況を報告
        progressReporter.runTaskTimer(plugin, 100L, 100L); // 5秒 = 100tick

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "並列計算開始... CPU使用: " + numThreads + "スレッド");
            }
        });

        // 事前計算削除 - String版プリセットを直接使用（完全にスレッドセーフ）

        // 並列処理のFutureリスト（スレッドセーフ）
        List<Future<IndexedBlockResult>> futures = Collections.synchronizedList(new ArrayList<>());

        // 道路建築アルゴリズム実装済み - 並列処理による高速化

        // Z軸方向の各セクションを並列処理で実行
        for (int zz = 0; zz <= roadPreset.getWidthZ()/2+1; zz++) {
            for(int j = 0; j < 2; j++) {
                final int finalZz = zz;
                final int finalJ = j;

                // 並列処理用のタスクを作成（事前計算された文字列を使用）
                Future<IndexedBlockResult> future = executor.submit(() -> {
                    try {
                        // スレッドローカルでpathのコピーを作成
                        List<Location> threadPath = new ArrayList<>(path);
                        return calculateBlocksForZSection(finalZz, finalJ, threadPath, roadPreset, completedTasks);
                    } catch (Exception e) {
                        plugin.getLogger().severe("並列計算タスク(zz=" + finalZz + ", j=" + finalJ + ")でエラー: " + e.getMessage());
                        e.printStackTrace();
                        throw new RuntimeException("並列計算エラー", e);
                    }
                });

                synchronized(futures) {
                    futures.add(future);
                }
            }
        }

        // 並列処理の結果を収集し、元の順序通りに結合
        int futuresSize;
        synchronized(futures) {
            futuresSize = futures.size();
        }
        IndexedBlockResult[] resultsArray = new IndexedBlockResult[futuresSize];
        try {
            for (int i = 0; i < futuresSize; i++) {
                Future<IndexedBlockResult> future;
                synchronized(futures) {
                    future = futures.get(i);
                }
                resultsArray[i] = future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            progressReporter.cancel(); // 進行状況報告タスクを停止

            // 詳細なエラーログを出力
            plugin.getLogger().severe("並列計算中に重大なエラーが発生しました:");
            plugin.getLogger().severe("エラータイプ: " + e.getClass().getSimpleName());
            plugin.getLogger().severe("エラーメッセージ: " + e.getMessage());
            if (e.getCause() != null) {
                plugin.getLogger().severe("原因: " + e.getCause().getClass().getSimpleName() + " - " + e.getCause().getMessage());
            }
            e.printStackTrace();

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    player.sendMessage(ChatColor.RED + "並列計算中にエラーが発生しました: " + e.getMessage());
                    player.sendMessage(ChatColor.RED + "詳細はサーバーログを確認してください");
                }
            });
            executor.shutdown();
            return;
        }

        executor.shutdown();
        progressReporter.cancel(); // 計算完了時に進行状況報告タスクを停止

        // 配列をリストに変換してソート（順序保持を厳密に保証）
        List<IndexedBlockResult> results = Arrays.asList(resultsArray);
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
                                                         AtomicInteger completedTasks) {
        try {
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

            int sliceIndex = (int) x;
            Location location = pathPoint.clone().add(rightVector.clone().multiply(z));

            out:
            for (int y = roadPreset.getMinY(); y <= roadPreset.getMaxY()+1; y++) {

                // String版プリセットから直接取得（完全にスレッドセーフ）
                if (sliceIndex >= 0 && sliceIndex < roadPreset.getSlices().size()) {
                    RoadPreset.PresetSlice slice = roadPreset.getSlices().get(sliceIndex);
                    String blockDataString = slice.getBlockDataStringRelativeToAxis(z, y, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());

                    if (blockDataString != null) {

                        Location worldLocation = location.clone().add(0,y,0);

                        // 完全String処理による回転（並列処理で安全）
                        String rotatedBlockDataString = StringBlockRotationUtil.rotateBlockDataString(blockDataString, Math.toRadians(yaw));

                        BlockData clonedBlockData;
                        try {
                            clonedBlockData = Bukkit.createBlockData(rotatedBlockDataString);
                        } catch (IllegalArgumentException e) {
                            // 回転処理でエラーが発生した場合は元のブロックデータを使用
                            plugin.getLogger().warning("回転処理エラー、元データを使用: " + blockDataString + " -> " + rotatedBlockDataString);
                            clonedBlockData = Bukkit.createBlockData(blockDataString);
                            rotatedBlockDataString = blockDataString; // Slab処理用にも元データを使用
                        }

                        // 坂を滑らかにするために、ハーフブロックの高さを調整（完全String処理）
                        if(rotatedBlockDataString.contains("_slab")) {
                            // 上のブロックが空気かどうかをString判定で確認
                            String aboveBlockDataString = null;
                            if (y + 1 <= roadPreset.getMaxY()) {
                                aboveBlockDataString = slice.getBlockDataStringRelativeToAxis(z, y + 1, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());
                            }

                            // ハーフブロックで上が空気の場合、地形に合わせてString操作で調整
                            boolean hasBlockAbove = (aboveBlockDataString != null && !aboveBlockDataString.contains("air"));

                            if (!hasBlockAbove) { // 上が空気または範囲外
                                // 地面の高さと比較してスラブタイプを決定
                                int groundY = (int) Math.floor(worldLocation.getY());
                                double heightAboveGround = worldLocation.getY() - groundY;

                                // String解析で元のスラブタイプを判定（BlockData操作完全回避）
                                boolean isOriginalBottom = rotatedBlockDataString.contains("type=bottom") ||
                                                         (!rotatedBlockDataString.contains("type=top") && !rotatedBlockDataString.contains("type=double"));

                                // 地面から0.5ブロック以下の場合はBOTTOMスラブ、それ以外はDOUBLE
                                if(isOriginalBottom) {
                                    if (heightAboveGround < 0.5) {
                                        break out;
                                    } else {
                                        // String操作でBOTTOMスラブに変更
                                        String modifiedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]*", "type=bottom");
                                        clonedBlockData = Bukkit.createBlockData(modifiedBlockDataString);
                                    }
                                } else {
                                    if (heightAboveGround < 0.5) {
                                        // String操作でBOTTOMスラブに変更
                                        String modifiedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]*", "type=bottom");
                                        clonedBlockData = Bukkit.createBlockData(modifiedBlockDataString);
                                    } else {
                                        // String操作でDOUBLEスラブに変更
                                        String modifiedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]*", "type=double");
                                        clonedBlockData = Bukkit.createBlockData(modifiedBlockDataString);
                                    }
                                }
                            } else {
                                // 上にブロックがある場合は通常のダブルスラブ（String操作）
                                String modifiedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]*", "type=double");
                                clonedBlockData = Bukkit.createBlockData(modifiedBlockDataString);
                            }
                        }

                        sectionOriginalBlocks.add(new BlockPlacementInfo(worldLocation,worldLocation.getBlock().getBlockData()));
                        sectionWorldBlocks.add(new BlockPlacementInfo(worldLocation, clonedBlockData));
                    }
                }
            }

            x += 0.5f;
            if (x >= roadPreset.getLengthX()) x = 0;
        }

            // 進行状況を更新（スレッドセーフ）
            completedTasks.incrementAndGet();

            return new IndexedBlockResult(zz, j, sectionWorldBlocks, sectionOriginalBlocks);

        } catch (Exception e) {
            plugin.getLogger().severe("calculateBlocksForZSection(zz=" + zz + ", j=" + j + ")でエラー:");
            plugin.getLogger().severe("エラータイプ: " + e.getClass().getSimpleName());
            plugin.getLogger().severe("エラーメッセージ: " + e.getMessage());
            plugin.getLogger().severe("スタックトレース:");
            e.printStackTrace();

            // 空の結果を返してエラーの伝播を防ぐ
            completedTasks.incrementAndGet();
            return new IndexedBlockResult(zz, j, new ArrayList<>(), new ArrayList<>());
        }
    }

}