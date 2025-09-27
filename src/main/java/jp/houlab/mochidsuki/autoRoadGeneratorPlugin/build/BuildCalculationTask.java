package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.RoadPreset;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route.RouteSession;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.StringBlockRotationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BuildCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;
    private final boolean onlyAir;
    private final boolean updateBlockData;
    private final UUID buildId;
    private final UUID edgeId;

    public record Vector3d(int x, int y, int z) {}
    public record CustomData(String blockDataString, double sourceX, double sourceY, double sourceZ, int presetZ, double pathDistance, int sliceIndex, double yaw) {}

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset, boolean onlyAir, boolean updateBlockData, UUID buildId, UUID edgeId) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.roadPreset = roadPreset;
        this.onlyAir = onlyAir;
        this.updateBlockData = updateBlockData;
        this.buildId = buildId;
        this.edgeId = edgeId;
    }

    @Override
    public void run() {
        List<Location> originalPath = routeSession.getCalculatedPath();
        if (originalPath == null || originalPath.isEmpty()) {
            BuildManager.addCanvasToSession(buildId, edgeId, new ConcurrentHashMap<>(), plugin, playerUUID, onlyAir, updateBlockData, roadPreset);
            return;
        }

        List<Location> path = new ArrayList<>(originalPath);

        ConcurrentHashMap<Vector3d, ConcurrentLinkedQueue<CustomData>> tempGridCanvas = new ConcurrentHashMap<>();
        List<Location> highResCenterPath = generateHighResPath(path, 0.1);

        List<Vector> directions = new ArrayList<>(highResCenterPath.size());
        List<Double> cumulativeDistances = new ArrayList<>(highResCenterPath.size());
        double currentDistance = 0.0;
        for (int i = 0; i < highResCenterPath.size(); i++) {
            directions.add(calculateDirectionVector(highResCenterPath, i));
            cumulativeDistances.add(currentDistance);
            if (i < highResCenterPath.size() - 1) {
                currentDistance += highResCenterPath.get(i).distance(highResCenterPath.get(i + 1));
            }
        }

        int presetDepth = roadPreset.getLengthX();
        int pointsPerChunk = (presetDepth > 0) ? presetDepth * 10 : highResCenterPath.size();
        int numThreads = Runtime.getRuntime().availableProcessors();
        ForkJoinPool executor = new ForkJoinPool(numThreads);

        List<List<Location>> pathChunks = new ArrayList<>();
        if (pointsPerChunk > 0 && pointsPerChunk < highResCenterPath.size()) {
            for (int i = 0; i < highResCenterPath.size(); i += pointsPerChunk) {
                int end = Math.min(i + pointsPerChunk, highResCenterPath.size());
                pathChunks.add(highResCenterPath.subList(i, end));
            }
        } else {
            pathChunks.add(highResCenterPath);
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < pathChunks.size(); i++) {
            List<Location> chunk = pathChunks.get(i);
            int startIndex = i * pointsPerChunk;
            int endIndex = startIndex + chunk.size();

            Future<Void> future = executor.submit(() -> {
                processPathChunk(chunk, directions.subList(startIndex, endIndex), cumulativeDistances.subList(startIndex, endIndex), tempGridCanvas);
                return null;
            });
            futures.add(future);
        }

        try {
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            plugin.getLogger().severe(plugin.getMessageManager().getMessage("log.path_chunk_processing_failed", edgeId, e.getMessage()));
            e.printStackTrace();
        } finally {
            executor.shutdown();
        }

        ConcurrentHashMap<Vector3d, AtomicReference<CustomData>> finalGridCanvas = new ConcurrentHashMap<>();
        tempGridCanvas.forEach((pos, queue) -> {
            if (queue == null || queue.isEmpty()) return;

            Map<String, Long> frequencies = queue.stream()
                    .collect(Collectors.groupingBy(CustomData::blockDataString, Collectors.counting()));

            long maxFreq = frequencies.values().stream().max(Long::compare).orElse(0L);

            List<String> topBlockDataStrings = frequencies.entrySet().stream()
                    .filter(entry -> entry.getValue() == maxFreq)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            CustomData winner = queue.stream()
                    .filter(data -> topBlockDataStrings.contains(data.blockDataString()))
                    .min(Comparator.comparingDouble(data -> distanceToGridCenter(data.sourceX(), data.sourceZ())))
                    .orElse(null);

            if (winner != null) {
                finalGridCanvas.put(pos, new AtomicReference<>(winner));
            }
        });

        BuildManager.addCanvasToSession(buildId, edgeId, finalGridCanvas, plugin, playerUUID, onlyAir, updateBlockData, roadPreset);
    }

    private void processPathChunk(List<Location> pathChunk, List<Vector> directions, List<Double> cumulativeDistances, ConcurrentHashMap<Vector3d, ConcurrentLinkedQueue<CustomData>> gridCanvas) {
        final Map<Double, Location> lastPoints = new HashMap<>();
        final Map<Double, Double> lastYaws = new HashMap<>();
        final Map<Double, Double> lastPatterns = new HashMap<>();

        for (int i = 0; i < pathChunk.size(); i++) {
            Location centerPoint = pathChunk.get(i);
            Vector direction = directions.get(i);
            double yaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
            double patternPosition = cumulativeDistances.get(i);
            Vector rightVector = new Vector(-direction.getZ(), 0, direction.getX()).normalize();

            int maxZ = roadPreset.getWidthZ() / 2;

            for (double zOffsetD = -maxZ; zOffsetD <= maxZ; zOffsetD += 0.1) {
                int roundedZOffset = (int) Math.round(zOffsetD);
                Location currentPoint = centerPoint.clone().add(rightVector.clone().multiply(zOffsetD));

                Location lastPoint = lastPoints.get(zOffsetD);
                Double lastYaw = lastYaws.get(zOffsetD);
                Double lastPattern = lastPatterns.get(zOffsetD);

                if (lastPoint != null) {
                    fillSegmentInGrid(lastPoint, currentPoint, lastPattern, patternPosition, lastYaw, yaw, roundedZOffset, roadPreset, gridCanvas);
                }

                lastPoints.put(zOffsetD, currentPoint);
                lastYaws.put(zOffsetD, yaw);
                lastPatterns.put(zOffsetD, patternPosition);
            }
        }
    }

    private void fillSegmentInGrid(Location start, Location end, double startPattern, double endPattern, double startYaw, double endYaw, int zOffset, RoadPreset preset, ConcurrentHashMap<Vector3d, ConcurrentLinkedQueue<CustomData>> gridCanvas) {
        Vector segment = end.toVector().subtract(start.toVector());
        double distance = segment.length();
        int steps = (int) Math.ceil(distance / 0.4);
        if (steps == 0) return;

        Vector stepVector = segment.clone().divide(new Vector(steps, steps, steps));
        double patternStep = (endPattern - startPattern) / steps;

        double yawDiff = endYaw - startYaw;
        if (yawDiff > 180) yawDiff -= 360;
        if (yawDiff < -180) yawDiff += 360;
        double yawStep = yawDiff / steps;

        Location currentLoc = start.clone();
        double currentPattern = startPattern;
        double currentYaw = startYaw;

        for (int i = 0; i < steps; i++) {
            int sliceIndex = (int) currentPattern % preset.getLengthX();
            RoadPreset.PresetSlice slice = preset.getSlices().get(sliceIndex);

            for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                String blockDataString = slice.getBlockDataStringRelativeToAxis(zOffset, y, preset.getAxisZOffset(), preset.getAxisYOffset());

                if (blockDataString != null && !blockDataString.equals("minecraft:air") && !blockDataString.startsWith("minecraft:air[")) {
                    Location blockLocation = currentLoc.clone().add(0, y, 0);
                    String finalBlockDataString = blockDataString;

                    if (finalBlockDataString.contains("_slab")) {
                        String aboveBlockDataString = (y + 1 <= preset.getMaxY()) ? slice.getBlockDataStringRelativeToAxis(zOffset, y + 1, preset.getAxisZOffset(), preset.getAxisYOffset()) : null;
                        boolean hasBlockAbove = (aboveBlockDataString != null && !aboveBlockDataString.contains("air"));

                        if (!hasBlockAbove) {
                            double heightAboveGround = blockLocation.getY() - blockLocation.getBlockY();
                            boolean isOriginalBottom = finalBlockDataString.contains("type=bottom") || (!finalBlockDataString.contains("type=top") && !finalBlockDataString.contains("type=double"));

                            if (isOriginalBottom) {
                                if (heightAboveGround < 0.5) {
                                    continue;
                                }
                            } else {
                                String newType = (heightAboveGround < 0.5) ? "bottom" : "double";
                                if (finalBlockDataString.contains("type=")) {
                                    finalBlockDataString = finalBlockDataString.replaceAll("type=[^,\\]]*", "type=" + newType);
                                } else if (finalBlockDataString.contains("[")) {
                                    finalBlockDataString = finalBlockDataString.replace("]", ",type=" + newType + "]");
                                } else {
                                    finalBlockDataString = finalBlockDataString + "[type=" + newType + "]";
                                }
                            }
                        } else {
                            if (finalBlockDataString.contains("type=")) {
                                finalBlockDataString = finalBlockDataString.replaceAll("type=[^,\\]]*", "type=double");
                            } else if (finalBlockDataString.contains("[")) {
                                finalBlockDataString = finalBlockDataString.replace("]", ",type=double]");
                            } else {
                                finalBlockDataString = finalBlockDataString + "[type=double]";
                            }
                        }
                    }

                    Vector3d gridKey = new Vector3d(blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());
                    CustomData newData = new CustomData(finalBlockDataString, blockLocation.getX(), blockLocation.getY(), blockLocation.getZ(), zOffset, currentPattern, sliceIndex, currentYaw);
                    gridCanvas.computeIfAbsent(gridKey, k -> new ConcurrentLinkedQueue<>()).add(newData);
                }
            }
            currentLoc.add(stepVector);
            currentPattern += patternStep;
            currentYaw += yawStep;
        }
    }

    private static double distanceToGridCenter(double x, double z) {
        double dx = x - (Math.floor(x) + 0.5);
        double dz = z - (Math.floor(z) + 0.5);
        return dx * dx + dz * dz;
    }

    private static boolean shouldReplaceData(CustomData newData, CustomData existingData) {
        int newAbsZ = Math.abs(newData.presetZ());
        int existingAbsZ = Math.abs(existingData.presetZ());
        if (newAbsZ != existingAbsZ) {
            return newAbsZ < existingAbsZ;
        }
        return distanceToGridCenter(newData.sourceX(), newData.sourceZ()) < distanceToGridCenter(existingData.sourceX(), existingData.sourceZ());
    }

    private static List<BlockPlacementInfo> convertGridToBlockPlacementList(ConcurrentHashMap<Vector3d, AtomicReference<CustomData>> gridCanvas, World world, RoadPreset roadPreset, AutoRoadGeneratorPluginMain plugin) {
        if (gridCanvas.isEmpty()) return new ArrayList<>();

        List<CustomData> allData = gridCanvas.values().stream()
                .map(AtomicReference::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<Integer> zValues = allData.stream()
                .map(CustomData::presetZ)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        List<Integer> zOrder = new ArrayList<>();
        if (!zValues.isEmpty()) {
            int zCount = zValues.size();
            for (int i = 0; i <= (zCount - 1) / 2; i++) {
                zOrder.add(zValues.get(i));
                if (i != zCount - 1 - i) {
                    zOrder.add(zValues.get(zCount - 1 - i));
                }
            }
        }

        Map<Integer, Integer> zOrderMap = new HashMap<>();
        for (int i = 0; i < zOrder.size(); i++) {
            zOrderMap.put(zOrder.get(i), i);
        }

        allData.sort(Comparator
                .comparing((CustomData data) -> zOrderMap.getOrDefault(data.presetZ(), Integer.MAX_VALUE))
                .thenComparing(CustomData::sliceIndex)
                .thenComparing(CustomData::sourceY)
        );

        List<BlockPlacementInfo> result = new ArrayList<>(allData.size());
        for (CustomData data : allData) {
            try {
                BlockData blockData = Bukkit.createBlockData(data.blockDataString());
                Location loc = new Location(world, Math.floor(data.sourceX()), Math.floor(data.sourceY()), Math.floor(data.sourceZ()));
                result.add(new BlockPlacementInfo(loc, blockData));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe(plugin.getMessageManager().getMessage("log.failed_create_block_data", data.blockDataString()));
                plugin.getLogger().severe(plugin.getMessageManager().getMessage("log.error_message", e.getMessage()));
                plugin.getLogger().severe(plugin.getMessageManager().getMessage("log.source_location", data.sourceX(), data.sourceY(), data.sourceZ()));
                plugin.getLogger().severe(plugin.getMessageManager().getMessage("log.yaw_slice_preset", data.yaw(), data.sliceIndex(), data.presetZ()));
                e.printStackTrace();
            }
        }
        return result;
    }

    private List<Location> generateHighResPath(List<Location> roadPath, double maxSegmentDistance) {
        List<Location> highResPath = new ArrayList<>();
        if (roadPath.isEmpty()) return highResPath;
        World world = roadPath.get(0).getWorld();
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
                    Vector intermediateVec = current.toVector().clone().multiply(1 - t).add(next.toVector().clone().multiply(t));
                    highResPath.add(new Location(world, intermediateVec.getX(), intermediateVec.getY(), intermediateVec.getZ()));
                }
            }
        }
        if (!roadPath.isEmpty()) highResPath.add(roadPath.get(roadPath.size() - 1));
        return highResPath;
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
        if (direction.length() < 0.001) {
            if (index > 0 && path.get(index).distanceSquared(path.get(index - 1)) > 0.0001) {
                return path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
            } else if (path.size() > index + 1 && path.get(index + 1).distanceSquared(path.get(index)) > 0.0001) {
                return path.get(index + 1).toVector().subtract(path.get(index).toVector()).normalize();
            } else {
                return new Vector(1, 0, 0);
            }
        }
        return direction.normalize();
    }

    public static class BuildManager {
        private static final Map<UUID, Map<UUID, ConcurrentHashMap<Vector3d, AtomicReference<CustomData>>>> buildSessions = new ConcurrentHashMap<>();
        private static final Map<UUID, Integer> expectedEdges = new ConcurrentHashMap<>();
        private static final Map<UUID, AtomicInteger> completedEdges = new ConcurrentHashMap<>();

        public static void startBuildSession(UUID buildId, int edgeCount) {
            buildSessions.put(buildId, new ConcurrentHashMap<>());
            expectedEdges.put(buildId, edgeCount);
            completedEdges.put(buildId, new AtomicInteger(0));
        }

        public static void addCanvasToSession(UUID buildId, UUID edgeId, ConcurrentHashMap<Vector3d, AtomicReference<CustomData>> canvas, AutoRoadGeneratorPluginMain plugin, UUID playerUUID, boolean onlyAir, boolean updateBlockData, RoadPreset roadPreset) {
            Map<UUID, ConcurrentHashMap<Vector3d, AtomicReference<CustomData>>> session = buildSessions.get(buildId);
            if (session == null) {
                plugin.getLogger().warning(plugin.getMessageManager().getMessage("log.unknown_build_session", buildId));
                return;
            }
            session.put(edgeId, canvas);

            int completed = completedEdges.get(buildId).incrementAndGet();
            int expected = expectedEdges.get(buildId);

            if (completed >= expected) {
                finishBuildSession(buildId, plugin, playerUUID, onlyAir, updateBlockData, roadPreset);
            }
        }

        private static void finishBuildSession(UUID buildId, AutoRoadGeneratorPluginMain plugin, UUID playerUUID, boolean onlyAir, boolean updateBlockData, RoadPreset roadPreset) {
            Map<UUID, ConcurrentHashMap<Vector3d, AtomicReference<CustomData>>> session = buildSessions.remove(buildId);
            expectedEdges.remove(buildId);
            completedEdges.remove(buildId);

            if (session == null) return;

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null) {
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "build.calculation_complete");
                }

                ConcurrentHashMap<Vector3d, AtomicReference<CustomData>> mergedCanvas = new ConcurrentHashMap<>();
                for (ConcurrentHashMap<Vector3d, AtomicReference<CustomData>> canvas : session.values()) {
                    canvas.forEach((pos, dataRef) -> {
                        mergedCanvas.merge(pos, dataRef, (existingRef, newRef) -> {
                            CustomData existingData = existingRef.get();
                            CustomData newData = newRef.get();
                            if (existingData == null) return newRef;
                            if (newData == null) return existingRef;

                            return shouldReplaceData(newData, existingData) ? newRef : existingRef;
                        });
                    });
                }

                // Conditionally rotate block data here, after merging and before final conversion
                if (updateBlockData) { // This corresponds to normal rotation behavior
                    mergedCanvas.forEach((pos, dataRef) -> {
                        CustomData originalData = dataRef.get();
                        if (originalData != null) {
                            // FIX: Add 90 degrees to the yaw to correct for the preset's assumed orientation (East vs South).
                            double correctedYaw = originalData.yaw() + 90.0;
                            String rotatedString = StringBlockRotationUtil.rotateBlockDataString(originalData.blockDataString(), Math.toRadians(correctedYaw));
                            CustomData rotatedData = new CustomData(rotatedString, originalData.sourceX(), originalData.sourceY(), originalData.sourceZ(), originalData.presetZ(), originalData.pathDistance(), originalData.sliceIndex(), originalData.yaw());
                            dataRef.set(rotatedData);
                        }
                    });
                }

                List<BlockPlacementInfo> worldBlocks = convertGridToBlockPlacementList(mergedCanvas, player.getWorld(), roadPreset, plugin);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player == null || !player.isOnline()) return;

                    Set<Location> placedBlockLocations = worldBlocks.stream().map(BlockPlacementInfo::position).collect(Collectors.toSet());
                    List<BlockPlacementInfo> originalBlocks = new ArrayList<>();
                    for (Location loc : placedBlockLocations) {
                        originalBlocks.add(new BlockPlacementInfo(loc, loc.getBlock().getBlockData()));
                    }

                    String modeText = onlyAir ? plugin.getMessageManager().getMessage("build.air_mode_text") : "";
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "build.integration_complete", worldBlocks.size(), modeText);

                    BuildHistoryManager.addBuildHistory(playerUUID, originalBlocks);
                    Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(worldBlocks);

                    new BuildPlacementTask(plugin, playerUUID, placementQueue, onlyAir, updateBlockData).runTaskTimer(plugin, 1, 1);
                });
            });
        }
    }
}
