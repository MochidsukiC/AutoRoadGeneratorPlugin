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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class BuildCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;
    private final boolean onlyAir;
    private final boolean updateBlockData;
    private static final double SLAB_THRESHOLD_EPSILON = 1e-6;

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

        List<Integer> executionOrder = zOffsets.stream()
                .map(z -> z)
                .collect(Collectors.toList());

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "並列計算開始... CPU使用: " + numThreads + "スレッド (" + executionOrder.size() + "スライス)");
            }
        });

        AtomicInteger completedTasks = new AtomicInteger(0);
        int totalTasks = executionOrder.size();
        long startTime = System.currentTimeMillis();

        BukkitRunnable progressReporter = new BukkitRunnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player == null || !player.isOnline()) {
                    this.cancel();
                    return;
                }

                int completed = completedTasks.get();
                if (completed >= totalTasks) {
                    this.cancel();
                    return;
                }

                int percent = (int) ((double) completed / totalTasks * 100);
                long elapsedTime = System.currentTimeMillis() - startTime;
                String etaText = "";
                if (completed > 0) {
                    long estimatedTotalTime = (elapsedTime * totalTasks) / completed;
                    long remainingTime = estimatedTotalTime - elapsedTime;
                    if (remainingTime > 1000) {
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

                player.sendMessage(ChatColor.GREEN + "並列計算進行: " + percent + "% (" + completed + "/" + totalTasks + " スライス完了)" + etaText);
            }
        };
        progressReporter.runTaskTimer(plugin, 60L, 60L); // 3秒ごとに実行

        List<Future<List<BlockPlacementInfo>>> futures = new ArrayList<>();

        for (int zOffset : executionOrder) {
            Future<List<BlockPlacementInfo>> future = executor.submit(() -> {
                try {
                    return calculateBlocksForLongitudinalSlice(path, zOffset);
                } catch (Exception e) {
                    plugin.getLogger().severe("並列計算タスク(zOffset=" + zOffset + ")でエラー: " + e.getMessage());
                    e.printStackTrace();
                    return Collections.emptyList();
                } finally {
                    completedTasks.incrementAndGet();
                }
            });
            futures.add(future);
        }

        List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
        LinkedHashSet<Location> placedBlockLocations = new LinkedHashSet<>();

        try {
            for (Future<List<BlockPlacementInfo>> future : futures) {
                List<BlockPlacementInfo> sliceBlocks = future.get();
                for (BlockPlacementInfo info : sliceBlocks) {
                    if (placedBlockLocations.add(info.position())) {
                        worldBlocks.add(info);
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            progressReporter.cancel();
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

        progressReporter.cancel();
        executor.shutdown();

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

    private List<BlockPlacementInfo> calculateBlocksForLongitudinalSlice(List<Location> centerPath, int zOffset) {
        List<Location> smoothOffsetPath = generateSmoothOffsetPath(centerPath, zOffset);
        List<Location> voxelizedPath = voxelizeOffsetPath(smoothOffsetPath);
        return stampRoadCrossSections(voxelizedPath, zOffset);
    }

    private List<BlockPlacementInfo> stampRoadCrossSections(List<Location> voxelizedPath, int zOffset) {
        List<BlockPlacementInfo> blocks = new ArrayList<>();
        float patternPosition = 0f;

        for (int i = 0; i < voxelizedPath.size(); i++) {
            Location pathPoint = voxelizedPath.get(i);
            Vector direction = calculateDirectionVector(voxelizedPath, i);
            double yaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));

            int sliceIndex = (int) patternPosition % roadPreset.getLengthX();
            RoadPreset.PresetSlice slice = roadPreset.getSlices().get(sliceIndex);

            yLoop: // Label for breaking the inner loop
            for (int y = roadPreset.getMinY(); y <= roadPreset.getMaxY(); y++) {
                String blockDataString = slice.getBlockDataStringRelativeToAxis(zOffset, y, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());

                if (blockDataString != null && !blockDataString.contains("air")) {
                    Location blockLocation = pathPoint.clone().add(0, y, 0);
                    BlockData blockData;

                    try {
                        String rotatedBlockDataString = StringBlockRotationUtil.rotateBlockDataString(blockDataString, Math.toRadians(yaw));

                        // --- START OF SLAB LOGIC ---
                        if (rotatedBlockDataString.contains("_slab")) {
                            String aboveBlockDataString = null;
                            if (y + 1 <= roadPreset.getMaxY()) {
                                aboveBlockDataString = slice.getBlockDataStringRelativeToAxis(zOffset, y + 1, roadPreset.getAxisZOffset(), roadPreset.getAxisYOffset());
                            }
                            boolean hasBlockAbove = (aboveBlockDataString != null && !aboveBlockDataString.contains("air"));

                            if (!hasBlockAbove) { // This is a surface slab
                                double heightInBlock = blockLocation.getY() - blockLocation.getBlockY();

                                boolean isOriginalBottom = rotatedBlockDataString.contains("type=bottom") ||
                                        (!rotatedBlockDataString.contains("type=top") && !rotatedBlockDataString.contains("type=double"));

                                if (isOriginalBottom) {
                                    // Carving logic: User confirmed this threshold is correct.
                                    if (heightInBlock < 0.5) {
                                        break yLoop;
                                    }
                                    rotatedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]+", "type=bottom");
                                } else { // Original is top or double: This is where BOTTOM vs DOUBLE is decided.
                                    // Use epsilon to handle floating point inaccuracies near the 0.5 threshold.
                                    if (heightInBlock < 0.5 - SLAB_THRESHOLD_EPSILON) {
                                        rotatedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]+", "type=bottom");
                                    } else {
                                        rotatedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]+", "type=double");
                                    }
                                }
                            } else { // This slab has a block above it
                                rotatedBlockDataString = rotatedBlockDataString.replaceAll("type=[^,\\]]+", "type=double");
                            }
                        }
                        // --- END OF SLAB LOGIC ---

                        blockData = Bukkit.createBlockData(rotatedBlockDataString);

                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Block data processing failed for '" + blockDataString + "'. Using original.");
                        try {
                            blockData = Bukkit.createBlockData(blockDataString);
                        } catch (IllegalArgumentException e2) {
                            plugin.getLogger().severe("Failed to create even original block data for: " + blockDataString);
                            continue; // Skip this invalid block
                        }
                    }

                    Location finalBlockLocation = new Location(blockLocation.getWorld(), blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());
                    blocks.add(new BlockPlacementInfo(finalBlockLocation, blockData));
                }
            }

            if (i < voxelizedPath.size() - 1) {
                patternPosition += voxelizedPath.get(i).distance(voxelizedPath.get(i + 1));
            }
        }
        return blocks;
    }

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
