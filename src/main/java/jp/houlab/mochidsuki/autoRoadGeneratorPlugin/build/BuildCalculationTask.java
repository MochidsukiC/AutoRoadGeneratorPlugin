package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.RoadPreset;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.StringBlockRotationUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class BuildCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;
    private final boolean onlyAir;
    private final boolean updateBlockData;

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset, boolean onlyAir, boolean updateBlockData) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.roadPreset = roadPreset;
        this.onlyAir = onlyAir;
        this.updateBlockData = updateBlockData;
    }

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset) {
        this(plugin, playerUUID, routeSession, roadPreset, false, true);
    }

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset, boolean onlyAir) {
        this(plugin, playerUUID, routeSession, roadPreset, onlyAir, true);
    }

    @Override
    public void run() {
        List<Location> originalPath = routeSession.getCalculatedPath();
        if (originalPath == null || originalPath.isEmpty()) {
            return;
        }

        List<Location> path = new ArrayList<>(originalPath);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "道路計算中... 経路長: " + path.size() + "ブロック");
            }
        });

        int numThreads = Runtime.getRuntime().availableProcessors();
        ForkJoinPool executor = new ForkJoinPool(numThreads);

        // 1. オーケストレーター: 設置順序リストの事前生成
        // 「外側から中心へ」の順序 `[-max, +max, -max+1, +max-1, ... , 0]` を生成
        List<Integer> zOffsets = new ArrayList<>();
        int maxZ = roadPreset.getWidthZ() / 2;
        for (int i = 0; i <= maxZ; i++) {
            int z1 = -maxZ + i;
            int z2 = maxZ - i;
            if (z1 != 0 && !zOffsets.contains(z1)) {
                zOffsets.add(z1);
            }
            if (z2 != 0 && !zOffsets.contains(z2)) {
                zOffsets.add(z2);
            }
        }
        if (!zOffsets.contains(0)) {
            zOffsets.add(0);
        }

        // プリセットの軸オフセットを適用
        List<Integer> executionOrder = zOffsets.stream()
                .map(z -> z + (roadPreset.getWidthZ() % 2 == 0 ? 0 : 0)) // 偶数幅の場合の調整（必要に応じて）
                .collect(Collectors.toList());


        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "並列計算開始... CPU使用: " + numThreads + "スレッド (" + executionOrder.size() + "スライス)");
            }
        });

        List<Future<List<BlockPlacementInfo>>> futures = new ArrayList<>();

        // 2. オーケストレーター: 各スライスの計算タスクを投入
        for (int zOffset : executionOrder) {
            Future<List<BlockPlacementInfo>> future = executor.submit(() -> {
                try {
                    return calculateBlocksForLongitudinalSlice(path, zOffset);
                } catch (Exception e) {
                    plugin.getLogger().severe("並列計算タスク(zOffset=" + zOffset + ")でエラー: " + e.getMessage());
                    e.printStackTrace();
                    return Collections.emptyList(); // Return empty list on error
                }
            });
            futures.add(future);
        }

        List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
        LinkedHashSet<Location> placedBlockLocations = new LinkedHashSet<>();

        try {
            // 3. 結果の統合と重複排除
            // 投入した順（外側から中心へ）に結果を待つ
            for (Future<List<BlockPlacementInfo>> future : futures) {
                List<BlockPlacementInfo> sliceBlocks = future.get();
                for (BlockPlacementInfo info : sliceBlocks) {
                    // 重複チェック。HashSetに追加できれば、それは新しい場所
                    if (placedBlockLocations.add(info.position())) {
                        worldBlocks.add(info);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            plugin.getLogger().severe("並列計算結果の収集中にエラー: " + e.getMessage());
            e.printStackTrace();
            executor.shutdown();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null) {
                    player.sendMessage(ChatColor.RED + "道路計算中にエラーが発生しました。");
                }
            });
            return;
        }

        executor.shutdown();

        // メインスレッドで安全に元のブロック情報を取得し、設置タスクを開始
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null || !player.isOnline()) return;

            List<BlockPlacementInfo> originalBlocks = new ArrayList<>();
            for (Location loc : placedBlockLocations) {
                originalBlocks.add(new BlockPlacementInfo(loc, loc.getBlock().getBlockData()));
            }

            String modeText = onlyAir ? " (空気ブロックのみ設置)" : "";
            player.sendMessage(ChatColor.GREEN + "計算完了! " + worldBlocks.size() + "ブロックの設置を開始します" + modeText);

            BuildHistoryManager.addBuildHistory(playerUUID, originalBlocks);
            Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(worldBlocks);
            new BuildPlacementTask(plugin, playerUUID, placementQueue, onlyAir, updateBlockData).runTaskTimer(plugin, 1, 1);
        });
    }

    /**
     * 2. 並列スライス生成タスク: 一本の縦列スライスのブロック計算を行う
     */
    private List<BlockPlacementInfo> calculateBlocksForLongitudinalSlice(List<Location> centerPath, int zOffset) {
        // 2.1. 独立オフセットパスの生成
        List<Location> smoothOffsetPath = generateSmoothOffsetPath(centerPath, zOffset);

        // 2.2. パスのボクセル化
        List<Location> voxelizedPath = voxelizeOffsetPath(smoothOffsetPath);

        // 2.3. ブロック配置計算
        return stampRoadCrossSections(voxelizedPath, zOffset);
    }

    /**
     * ボクセル化されたパスに沿って道路断面を配置する
     */
    private List<BlockPlacementInfo> stampRoadCrossSections(List<Location> voxelizedPath, int zOffset) {
        List<BlockPlacementInfo> blocks = new ArrayList<>();
        float patternPosition = 0f;

        for (int i = 0; i < voxelizedPath.size(); i++) {
            Location pathPoint = voxelizedPath.get(i);
            Vector direction = calculateDirectionVector(voxelizedPath, i);
            double yaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));

            int sliceIndex = (int) patternPosition % roadPreset.getLengthX();
            RoadPreset.PresetSlice slice = roadPreset.getSlices().get(sliceIndex);

            for (int y = roadPreset.getMinY(); y <= roadPreset.getMaxY(); y++) {
                String blockDataString = slice.getBlockDataStringRelativeToAxis(zOffset, y, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());

                if (blockDataString != null && !blockDataString.contains("air")) {
                    Location blockLocation = pathPoint.clone().add(0, y, 0);

                    try {
                        String rotatedBlockDataString = StringBlockRotationUtil.rotateBlockDataString(blockDataString, Math.toRadians(yaw));
                        BlockData blockData = Bukkit.createBlockData(rotatedBlockDataString);
                        Location finalBlockLocation = new Location(blockLocation.getWorld(), blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());
                        blocks.add(new BlockPlacementInfo(finalBlockLocation, blockData));
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Block rotation failed for '" + blockDataString + "'. Using original.");
                        try {
                            BlockData originalBlockData = Bukkit.createBlockData(blockDataString);
                            Location finalBlockLocation = new Location(blockLocation.getWorld(), blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());
                            blocks.add(new BlockPlacementInfo(finalBlockLocation, originalBlockData));
                        } catch (IllegalArgumentException e2) {
                            plugin.getLogger().severe("Failed to create even original block data for: " + blockDataString);
                        }
                    }
                }
            }

            if (i < voxelizedPath.size() - 1) {
                patternPosition += voxelizedPath.get(i).distance(voxelizedPath.get(i + 1));
            }
        }
        return blocks;
    }

    // --- WallCalculationTaskから移植・改変したメソッド群 ---

    private List<Location> generateSmoothOffsetPath(List<Location> roadPath, double offset) {
        List<Location> highResPath = new ArrayList<>();
        double maxSegmentDistance = 0.5;

        for (int i = 0; i < roadPath.size() - 1; i++) {
            Location current = roadPath.get(i);
            Location next = roadPath.get(i + 1);
            highResPath.add(current);
            double segmentDistance = current.distance(next);
            if (segmentDistance > maxSegmentDistance) {
                int subdivisions = (int) Math.ceil(segmentDistance / maxSegmentDistance);
                subdivisions = Math.min(subdivisions, 1000);
                for (int j = 1; j < subdivisions; j++) {
                    double t = (double) j / subdivisions;
                    Location intermediate = current.clone().multiply(1 - t).add(next.toVector().multiply(t));
                    intermediate.setWorld(current.getWorld());
                    highResPath.add(intermediate);
                }
            }
        }
        if (!roadPath.isEmpty()) {
            highResPath.add(roadPath.get(roadPath.size() - 1));
        }

        List<Location> initialOffsetPath = new ArrayList<>();
        for (int i = 0; i < highResPath.size(); i++) {
            Vector rightVector = calculateRightVector(highResPath, i);
            Location offsetPoint = highResPath.get(i).clone().add(rightVector.multiply(offset));
            initialOffsetPath.add(offsetPoint);
        }

        return recursivelySubdivideUntilDense(initialOffsetPath, maxSegmentDistance);
    }

    private List<Location> recursivelySubdivideUntilDense(List<Location> path, double maxDistance) {
        if (path.size() < 2) return new ArrayList<>(path);
        List<Location> result = new ArrayList<>();
        boolean needsAnotherPass = false;
        for (int i = 0; i < path.size() - 1; i++) {
            Location current = path.get(i);
            Location next = path.get(i + 1);
            result.add(current);
            double segmentDistance = current.distance(next);
            if (segmentDistance > maxDistance) {
                needsAnotherPass = true;
                int subdivisions = (int) Math.ceil(segmentDistance / maxDistance);
                subdivisions = Math.min(subdivisions, 100);
                for (int j = 1; j < subdivisions; j++) {
                    double t = (double) j / subdivisions;
                    Location intermediate = current.clone().multiply(1 - t).add(next.toVector().multiply(t));
                    intermediate.setWorld(current.getWorld());
                    result.add(intermediate);
                }
            }
        }
        if (!path.isEmpty()) result.add(path.get(path.size() - 1));
        return needsAnotherPass ? recursivelySubdivideUntilDense(result, maxDistance) : result;
    }

    private Vector calculateRightVector(List<Location> path, int index) {
        Vector forwardVector;
        if (index == 0) {
            forwardVector = path.size() > 1 ? path.get(1).toVector().subtract(path.get(0).toVector()).normalize() : new Vector(1, 0, 0);
        } else if (index == path.size() - 1) {
            forwardVector = path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
        } else {
            Vector incoming = path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
            Vector outgoing = path.get(index + 1).toVector().subtract(path.get(index).toVector()).normalize();
            forwardVector = incoming.add(outgoing).multiply(0.5).normalize();
        }
        return new Vector(-forwardVector.getZ(), 0, forwardVector.getX()).normalize();
    }

    private List<Location> voxelizeOffsetPath(List<Location> smoothPath) {
        if (smoothPath.isEmpty()) return new ArrayList<>();
        List<Location> snappedPath = new ArrayList<>();
        Location lastSnapped = null;
        for (Location smoothPoint : smoothPath) {
            Location snapped = new Location(smoothPoint.getWorld(), Math.floor(smoothPoint.getX()) + 0.5, smoothPoint.getY(), Math.floor(smoothPoint.getZ()) + 0.5);
            if (lastSnapped == null || !isSameBlockXZ(lastSnapped, snapped)) {
                if (lastSnapped != null) {
                    addIntermediatePoints(snappedPath, lastSnapped, snapped);
                }
                snappedPath.add(snapped);
                lastSnapped = snapped;
            }
        }
        return snappedPath;
    }

    private void addIntermediatePoints(List<Location> path, Location from, Location to) {
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();
        if (Math.abs(deltaX) > 0.1 && Math.abs(deltaZ) > 0.1) {
            double yAtCorner = from.getY() + (to.getY() - from.getY()) * (Math.abs(deltaX) / (Math.abs(deltaX) + Math.abs(deltaZ)));
            Location corner = new Location(from.getWorld(), to.getX(), yAtCorner, from.getZ());
            path.add(corner);
        }
    }

    private boolean isSameBlockXZ(Location loc1, Location loc2) {
        return loc1.getBlockX() == loc2.getBlockX() && loc1.getBlockZ() == loc2.getBlockZ();
    }

    private Vector calculateDirectionVector(List<Location> path, int index) {
        if (path.size() < 2) return new Vector(1, 0, 0);
        Vector direction;
        if (index == 0) {
            direction = path.get(1).toVector().subtract(path.get(0).toVector());
        } else if (index == path.size() - 1) {
            direction = path.get(index).toVector().subtract(path.get(index - 1).toVector());
        } else {
            Vector incoming = path.get(index).toVector().subtract(path.get(index - 1).toVector());
            Vector outgoing = path.get(index + 1).toVector().subtract(path.get(index).toVector());
            if (incoming.length() > 0.001) incoming.normalize();
            if (outgoing.length() > 0.001) outgoing.normalize();
            direction = incoming.add(outgoing).multiply(0.5);
        }
        if (direction.length() < 0.001) return new Vector(1, 0, 0);
        return direction.normalize();
    }
}
