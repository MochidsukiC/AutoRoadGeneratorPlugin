package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.build;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.preset.WallPreset;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class WallCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final WallPreset wallPreset;
    private final double xOffset;
    private final double yOffset;
    private final boolean onlyAir;
    private final boolean updateBlockData;
    private final UUID buildId;
    private final UUID edgeId;

    public WallCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession,
                              WallPreset wallPreset, double xOffset, double yOffset, boolean onlyAir, boolean updateBlockData, UUID buildId, UUID edgeId) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.wallPreset = wallPreset;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.onlyAir = onlyAir;
        this.updateBlockData = updateBlockData;
        this.buildId = buildId;
        this.edgeId = edgeId;
    }

    @Override
    public void run() {
        List<Location> originalPath = routeSession.getCalculatedPath();
        if (originalPath == null || originalPath.isEmpty()) {
            BuildManager.addCanvasToSession(buildId, edgeId, new HashMap<>(), plugin, playerUUID, onlyAir, updateBlockData);
            return;
        }

        List<Location> path = new ArrayList<>(originalPath);
        Map<Location, BlockData> wallBlocks = new HashMap<>();

        try {
            List<Location> smoothOffsetPath = generateSmoothOffsetPath(path, xOffset);
            List<Location> snappedPath = voxelizeOffsetPath(smoothOffsetPath);
            wallBlocks = stampWallCrossSections(snappedPath, wallPreset);

        } catch (Exception e) {
            plugin.getLogger().severe("Error during wall calculation: " + e.getMessage());
            e.printStackTrace();
        } finally {
            BuildManager.addCanvasToSession(buildId, edgeId, wallBlocks, plugin, playerUUID, onlyAir, updateBlockData);
        }
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
        if (!roadPath.isEmpty()) highResPath.add(roadPath.get(roadPath.size() - 1));

        List<Location> initialOffsetPath = new ArrayList<>();
        for (int i = 0; i < highResPath.size(); i++) {
            Vector rightVector = calculateRightVector(highResPath, i);
            initialOffsetPath.add(highResPath.get(i).clone().add(rightVector.multiply(offset)));
        }

        return recursivelySubdivideUntilDense(initialOffsetPath, maxSegmentDistance);
    }

    private List<Location> recursivelySubdivideUntilDense(List<Location> path, double maxDistance) {
        if (path.size() < 2) return new ArrayList<>(path);
        List<Location> result = new ArrayList<>();
        boolean needsAnotherPass = false;
        result.add(path.get(0));
        for (int i = 0; i < path.size() - 1; i++) {
            Location current = path.get(i);
            Location next = path.get(i + 1);
            double segmentDistance = current.distance(next);
            if (segmentDistance > maxDistance) {
                needsAnotherPass = true;
                int subdivisions = (int) Math.ceil(segmentDistance / maxDistance);
                subdivisions = Math.min(subdivisions, 100);
                for (int j = 1; j <= subdivisions; j++) {
                    double t = (double) j / subdivisions;
                    Location intermediate = current.clone().multiply(1 - t).add(next.toVector().multiply(t));
                    intermediate.setWorld(current.getWorld());
                    result.add(intermediate);
                }
            } else {
                result.add(next);
            }
        }
        return needsAnotherPass ? recursivelySubdivideUntilDense(result, maxDistance) : result;
    }

    private Vector calculateRightVector(List<Location> path, int index) {
        Vector forwardVector;
        if (path.size() < 2) return new Vector(0, 0, 1);
        if (index == 0) {
            forwardVector = path.get(1).toVector().subtract(path.get(0).toVector());
        } else if (index == path.size() - 1) {
            forwardVector = path.get(index).toVector().subtract(path.get(index - 1).toVector());
        } else {
            Vector incoming = path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
            Vector outgoing = path.get(index + 1).toVector().subtract(path.get(index).toVector()).normalize();
            forwardVector = incoming.add(outgoing).multiply(0.5);
        }
        if (forwardVector.lengthSquared() < 1.0E-6) return new Vector(0, 0, 1);
        return new Vector(-forwardVector.getZ(), 0, forwardVector.getX()).normalize();
    }

    private List<Location> voxelizeOffsetPath(List<Location> smoothPath) {
        if (smoothPath.isEmpty()) return new ArrayList<>();
        List<Location> snappedPath = new ArrayList<>();
        Location lastSnapped = null;
        for (Location smoothPoint : smoothPath) {
            Location snapped = new Location(smoothPoint.getWorld(), Math.floor(smoothPoint.getX()) + 0.5, smoothPoint.getY(), Math.floor(smoothPoint.getZ()) + 0.5);
            if (lastSnapped == null) {
                snappedPath.add(snapped);
                lastSnapped = snapped;
            } else if (!isSameBlockPosition(lastSnapped, snapped)) {
                addIntermediatePoints(snappedPath, lastSnapped, snapped);
                lastSnapped = snappedPath.get(snappedPath.size() - 1);
            }
        }
        return snappedPath;
    }

    private boolean isSameBlockPosition(Location loc1, Location loc2) {
        return loc1.getBlockX() == loc2.getBlockX() && loc1.getBlockZ() == loc2.getBlockZ();
    }

    private void addIntermediatePoints(List<Location> path, Location from, Location to) {
        int x0 = from.getBlockX();
        int z0 = from.getBlockZ();
        int x1 = to.getBlockX();
        int z1 = to.getBlockZ();
        double y0 = from.getY();
        double y1 = to.getY();
        int dx = x1 - x0;
        int dz = z1 - z0;
        int totalSteps = Math.abs(dx) + Math.abs(dz);
        if (totalSteps == 0) return;

        int currentX = x0;
        int currentZ = z0;
        int sx = Integer.compare(dx, 0);
        int sz = Integer.compare(dz, 0);
        int stepsDone = 0;

        for (int i = 0; i < Math.abs(dx); i++) {
            currentX += sx;
            stepsDone++;
            double progress = (double) stepsDone / totalSteps;
            double interpY = y0 + (y1 - y0) * progress;
            path.add(new Location(from.getWorld(), currentX + 0.5, interpY, currentZ + 0.5));
        }
        for (int i = 0; i < Math.abs(dz); i++) {
            currentZ += sz;
            stepsDone++;
            double progress = (double) stepsDone / totalSteps;
            double interpY = y0 + (y1 - y0) * progress;
            path.add(new Location(from.getWorld(), currentX + 0.5, interpY, currentZ + 0.5));
        }
    }

    private Map<Location, BlockData> stampWallCrossSections(List<Location> snappedPath, WallPreset preset) {
        Map<Location, BlockData> wallBlocks = new HashMap<>();
        float cumulativeDistance = 0f;
        for (int i = 0; i < snappedPath.size(); i++) {
            Location pathPoint = snappedPath.get(i);
            Vector direction = calculateDirectionVector(snappedPath, i);
            double yaw = Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
            Vector rightVector = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
            Vector upVector = new Vector(0, 1, 0);
            int sliceIndex = (int) cumulativeDistance % preset.getLengthX();
            if (sliceIndex >= 0 && sliceIndex < preset.getSlices().size()) {
                WallPreset.WallSlice slice = preset.getSlices().get(sliceIndex);
                for (int z = preset.getMinZ(); z <= preset.getMaxZ(); z++) {
                    for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                        String blockDataString = slice.getBlockDataStringRelativeToAxis(z, y, preset.getAxisZOffset(), preset.getAxisYOffset());
                        if (blockDataString != null) {
                            Location blockLocation = pathPoint.clone().add(rightVector.clone().multiply(z)).add(upVector.clone().multiply(y + yOffset));
                            try {
                                // Add 90 degrees correction like in road system
                                double correctedYaw = yaw + 90.0;
                                String rotatedBlockDataString = StringBlockRotationUtil.rotateBlockDataString(blockDataString, Math.toRadians(correctedYaw));
                                BlockData rotatedBlockData = Bukkit.createBlockData(rotatedBlockDataString);
                                wallBlocks.put(blockLocation.getBlock().getLocation(), rotatedBlockData);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Failed to rotate or create block data: " + blockDataString);
                            }
                        }
                    }
                }
            }
            if (i < snappedPath.size() - 1) {
                cumulativeDistance += snappedPath.get(i).distance(snappedPath.get(i + 1));
            }
        }
        return wallBlocks;
    }

    private Vector calculateDirectionVector(List<Location> path, int index) {
        if (path.size() < 2) {
            return new Vector(1, 0, 0); // Default forward (X-axis)
        }

        Vector direction;
        if (index > 0) {
            direction = path.get(index).toVector().subtract(path.get(index - 1).toVector());
        } else {
            direction = path.get(1).toVector().subtract(path.get(0).toVector());
        }

        if (direction.lengthSquared() < 1.0E-6) {
            if (path.size() > index + 1) {
                Vector nextDir = path.get(index + 1).toVector().subtract(path.get(index).toVector());
                if(nextDir.lengthSquared() > 1.0E-6) return nextDir.normalize();
            }
            return new Vector(1, 0, 0);
        }

        return direction.normalize();
    }

    public static class BuildManager {
        private static final Map<UUID, Map<UUID, Map<Location, BlockData>>> buildSessions = new ConcurrentHashMap<>();
        private static final Map<UUID, Integer> expectedEdges = new ConcurrentHashMap<>();
        private static final Map<UUID, AtomicInteger> completedEdges = new ConcurrentHashMap<>();

        public static void startBuildSession(UUID buildId, int edgeCount) {
            buildSessions.put(buildId, new ConcurrentHashMap<>());
            expectedEdges.put(buildId, edgeCount);
            completedEdges.put(buildId, new AtomicInteger(0));
        }

        public static void addCanvasToSession(UUID buildId, UUID edgeId, Map<Location, BlockData> canvas, AutoRoadGeneratorPluginMain plugin, UUID playerUUID, boolean onlyAir, boolean updateBlockData) {
            Map<UUID, Map<Location, BlockData>> session = buildSessions.get(buildId);
            if (session == null) return;
            session.put(edgeId, canvas);
            int completed = completedEdges.get(buildId).incrementAndGet();
            int expected = expectedEdges.get(buildId);
            if (completed >= expected) {
                finishBuildSession(buildId, plugin, playerUUID, onlyAir, updateBlockData);
            }
        }

        private static void finishBuildSession(UUID buildId, AutoRoadGeneratorPluginMain plugin, UUID playerUUID, boolean onlyAir, boolean updateBlockData) {
            Map<UUID, Map<Location, BlockData>> session = buildSessions.remove(buildId);
            expectedEdges.remove(buildId);
            completedEdges.remove(buildId);
            if (session == null) return;

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null) player.sendMessage(ChatColor.YELLOW + "Integrating wall sections...");

                Map<Location, BlockData> mergedCanvas = new HashMap<>();
                for (Map<Location, BlockData> canvas : session.values()) {
                    mergedCanvas.putAll(canvas);
                }

                List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
                List<BlockPlacementInfo> originalBlocks = new ArrayList<>();
                for (Map.Entry<Location, BlockData> entry : mergedCanvas.entrySet()) {
                    Location loc = entry.getKey();
                    originalBlocks.add(new BlockPlacementInfo(loc, loc.getBlock().getBlockData()));
                    worldBlocks.add(new BlockPlacementInfo(loc, entry.getValue()));
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player == null || !player.isOnline()) return;
                    String modeText = onlyAir ? " (Air Only)" : "";
                    player.sendMessage(ChatColor.GREEN + "Integration complete! Placing " + worldBlocks.size() + " blocks" + modeText);
                    BuildHistoryManager.addBuildHistory(playerUUID, originalBlocks);
                    new BuildPlacementTask(plugin, playerUUID, new ConcurrentLinkedQueue<>(worldBlocks), onlyAir, updateBlockData).runTaskTimer(plugin, 1, 1);
                });
            });
        }
    }
}
