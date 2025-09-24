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
import java.util.concurrent.ConcurrentLinkedQueue;

public class WallCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final WallPreset wallPreset;
    private final double xOffset; // X Offset from road centerline (positive = right side, negative = left side)
    private final double yOffset; // Y Offset (vertical displacement)
    private final boolean onlyAir;
    private final boolean updateBlockData; // ブロック更新を行うかどうか

    public WallCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession,
                              WallPreset wallPreset, double xOffset, double yOffset, boolean onlyAir, boolean updateBlockData) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.wallPreset = wallPreset;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.onlyAir = onlyAir;
        this.updateBlockData = updateBlockData;
    }

    // Existing constructor compatibility
    public WallCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession,
                              WallPreset wallPreset, double offset) {
        this(plugin, playerUUID, routeSession, wallPreset, offset, 0.0, false, true); // デフォルトでブロック更新有効
    }

    // onlyAirパラメータのみのコンストラクタ
    public WallCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession,
                              WallPreset wallPreset, double offset, boolean onlyAir) {
        this(plugin, playerUUID, routeSession, wallPreset, offset, 0.0, onlyAir, true); // デフォルトでブロック更新有効
    }

    @Override
    public void run() {
        List<Location> originalPath = routeSession.getCalculatedPath();
        if (originalPath == null || originalPath.isEmpty()) {
            return;
        }

        // Thread-safe copy
        List<Location> path = new ArrayList<>(originalPath);

        // Notify player of calculation start
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "塀計算中... 道路経路長: " + path.size() + "ブロック、オフセット: " + xOffset);
            }
        });

        try {
            // Step A: Generate smooth offset path
            List<Location> smoothOffsetPath = generateSmoothOffsetPath(path, xOffset);

            // Step B: Voxelize the offset path (grid snapping)
            List<Location> snappedPath = voxelizeOffsetPath(smoothOffsetPath);

            // Step B.5: Apply curve-specific processing based on curvature and offset
            if (xOffset != 0) {
                double curvature = calculateAverageCurvature(path);
                boolean isInnerCurve = isInnerCurve(curvature, xOffset);

                if (isInnerCurve) {
                    // Inner curve: apply thinning to prevent over-dense placement
                    snappedPath = thinInnerCurvePath(snappedPath);
                }
            }
            // Outer curves and centerline (xOffset == 0) use path as-is

            // Step C: Stamp wall preset cross-sections
            Map<Location, BlockData> wallBlocks = stampWallCrossSections(snappedPath, wallPreset);

            // Convert to placement info
            List<BlockPlacementInfo> worldBlocks = new ArrayList<>();
            List<BlockPlacementInfo> originalBlocks = new ArrayList<>();

            for (Map.Entry<Location, BlockData> entry : wallBlocks.entrySet()) {
                Location loc = entry.getKey();
                BlockData newData = entry.getValue();

                originalBlocks.add(new BlockPlacementInfo(loc, loc.getBlock().getBlockData()));
                worldBlocks.add(new BlockPlacementInfo(loc, newData));
            }

            // Notify completion
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    String modeText = onlyAir ? " (空気ブロックのみ設置)" : "";
                    player.sendMessage(ChatColor.GREEN + "塀計算完了! " + worldBlocks.size() + "ブロックの設置を開始します" + modeText);
                }
            });

            // Add to build history and start placement
            BuildHistoryManager.addBuildHistory(playerUUID, originalBlocks);
            Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>(worldBlocks);
            new BuildPlacementTask(plugin, playerUUID, placementQueue, onlyAir, updateBlockData).runTaskTimer(plugin, 1, 1);

        } catch (Exception e) {
            plugin.getLogger().severe("塀計算中にエラーが発生しました: " + e.getMessage());
            e.printStackTrace();

            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player != null && player.isOnline()) {
                    player.sendMessage(ChatColor.RED + "塀計算中にエラーが発生しました: " + e.getMessage());
                }
            });
        }
    }

    /**
     * Step A: Generate smooth high-resolution offset path from road centerline
     */
    private List<Location> generateSmoothOffsetPath(List<Location> roadPath, double offset) {
        List<Location> highResPath = new ArrayList<>();

        // Maximum allowed distance between consecutive points to ensure grid coverage
        double maxSegmentDistance = 0.5; // Half block to guarantee grid coverage

        // First, create high-resolution version of the road path
        for (int i = 0; i < roadPath.size() - 1; i++) {
            Location current = roadPath.get(i);
            Location next = roadPath.get(i + 1);

            highResPath.add(current);

            // Calculate actual distance between current and next points
            double segmentDistance = current.distance(next);

            // Only subdivide if the segment is longer than our maximum allowed distance
            if (segmentDistance > maxSegmentDistance) {
                // Calculate number of subdivisions needed to keep all segments <= maxSegmentDistance
                int subdivisions = (int) Math.ceil(segmentDistance / maxSegmentDistance);

                // Safety cap to prevent excessive computation
                subdivisions = Math.min(subdivisions, 1000);

                for (int j = 1; j < subdivisions; j++) {
                    double t = (double) j / subdivisions;
                    Location intermediate = current.clone().multiply(1 - t).add(next.toVector().multiply(t));
                    intermediate.setWorld(current.getWorld());
                    highResPath.add(intermediate);
                }
            }
        }

        // Add the last point
        if (!roadPath.isEmpty()) {
            highResPath.add(roadPath.get(roadPath.size() - 1));
        }

        // Now generate offset path from high-resolution road path
        List<Location> initialOffsetPath = new ArrayList<>();

        for (int i = 0; i < highResPath.size(); i++) {
            Location current = highResPath.get(i);

            // Calculate normal vector (right direction) at this point
            Vector rightVector = calculateRightVector(highResPath, i);

            // Apply offset (positive offset = right side, negative offset = left side)
            Location offsetPoint = current.clone().add(rightVector.multiply(offset));
            initialOffsetPath.add(offsetPoint);
        }

        // CRITICAL: Recursively subdivide offset path until ALL segments are <= maxSegmentDistance
        List<Location> finalOffsetPath = recursivelySubdivideUntilDense(initialOffsetPath, maxSegmentDistance);

        return finalOffsetPath;
    }

    /**
     * Recursively subdivide path until all segments are <= maxDistance
     * This ensures complete grid coverage regardless of offset distance
     */
    private List<Location> recursivelySubdivideUntilDense(List<Location> path, double maxDistance) {
        if (path.size() < 2) {
            return new ArrayList<>(path);
        }

        List<Location> result = new ArrayList<>();
        boolean needsAnotherPass = false;

        for (int i = 0; i < path.size() - 1; i++) {
            Location current = path.get(i);
            Location next = path.get(i + 1);

            result.add(current);

            double segmentDistance = current.distance(next);

            if (segmentDistance > maxDistance) {
                // Need to subdivide this segment
                needsAnotherPass = true;

                int subdivisions = (int) Math.ceil(segmentDistance / maxDistance);
                subdivisions = Math.min(subdivisions, 100); // Safety cap per segment

                for (int j = 1; j < subdivisions; j++) {
                    double t = (double) j / subdivisions;
                    Location intermediate = current.clone().multiply(1 - t).add(next.toVector().multiply(t));
                    intermediate.setWorld(current.getWorld());
                    result.add(intermediate);
                }
            }
        }

        // Add the last point
        if (!path.isEmpty()) {
            result.add(path.get(path.size() - 1));
        }

        // If any segment was subdivided, recursively check again
        if (needsAnotherPass) {
            return recursivelySubdivideUntilDense(result, maxDistance);
        }

        return result;
    }

    /**
     * Calculate right vector (normal to path direction) at given index
     */
    private Vector calculateRightVector(List<Location> path, int index) {
        Vector forwardVector;

        if (index == 0) {
            // First point: use direction to next point
            if (path.size() > 1) {
                forwardVector = path.get(1).toVector().subtract(path.get(0).toVector()).normalize();
            } else {
                forwardVector = new Vector(1, 0, 0); // Default forward
            }
        } else if (index == path.size() - 1) {
            // Last point: use direction from previous point
            forwardVector = path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
        } else {
            // Middle point: use average of incoming and outgoing directions
            Vector incoming = path.get(index).toVector().subtract(path.get(index - 1).toVector()).normalize();
            Vector outgoing = path.get(index + 1).toVector().subtract(path.get(index).toVector()).normalize();
            forwardVector = incoming.add(outgoing).multiply(0.5).normalize();
        }

        // Calculate right vector (90 degrees clockwise from forward, on horizontal plane)
        Vector rightVector = new Vector(-forwardVector.getZ(), 0, forwardVector.getX()).normalize();

        return rightVector;
    }

    /**
     * Step B: Voxelize (grid snap) the smooth offset path
     * This creates a path that only uses block corners, preventing connection gaps
     */
    private List<Location> voxelizeOffsetPath(List<Location> smoothPath) {
        if (smoothPath.isEmpty()) {
            return new ArrayList<>();
        }

        List<Location> snappedPath = new ArrayList<>();
        Location lastSnapped = null;

        // Process high-resolution smooth path and snap to grid
        for (Location smoothPoint : smoothPath) {
            // Snap to block corner
            Location snapped = new Location(
                smoothPoint.getWorld(),
                Math.floor(smoothPoint.getX()) + 0.5,
                smoothPoint.getY(), // Keep Y as-is for slope handling
                Math.floor(smoothPoint.getZ()) + 0.5
            );

            // Only add if different from last snapped point (avoids duplicates)
            if (lastSnapped == null || !isSameBlockPosition(lastSnapped, snapped)) {
                // Add intermediate points if we've moved diagonally
                if (lastSnapped != null) {
                    addIntermediatePoints(snappedPath, lastSnapped, snapped);
                } else {
                    snappedPath.add(snapped);
                }
                lastSnapped = snapped;
            }
        }

        return snappedPath;
    }

    /**
     * Check if two locations are at the same block position
     */
    private boolean isSameBlockPosition(Location loc1, Location loc2) {
        return Math.abs(loc1.getX() - loc2.getX()) < 0.1 &&
               Math.abs(loc1.getZ() - loc2.getZ()) < 0.1;
    }

    /**
     * Add intermediate points to ensure path only moves in cardinal directions
     */
    private void addIntermediatePoints(List<Location> path, Location from, Location to) {
        double deltaX = to.getX() - from.getX();
        double deltaZ = to.getZ() - from.getZ();

        // If moving diagonally, add intermediate point to make L-shaped path
        if (Math.abs(deltaX) > 0.1 && Math.abs(deltaZ) > 0.1) {
            // Add corner point (prefer X movement first, then Z)
            Location corner = new Location(from.getWorld(), to.getX(), from.getY(), from.getZ());
            path.add(corner);
        }

        path.add(to);
    }

    /**
     * Step C: Stamp wall preset 3D slices along the snapped path
     */
    private Map<Location, BlockData> stampWallCrossSections(List<Location> snappedPath, WallPreset preset) {
        Map<Location, BlockData> wallBlocks = new HashMap<>();

        // Track position along the path for pattern repetition
        // Use cumulative distance for proper pattern positioning
        float cumulativeDistance = 0f;

        for (int i = 0; i < snappedPath.size(); i++) {
            Location pathPoint = snappedPath.get(i);

            // Calculate direction at this point
            Vector direction = calculateDirectionVector(snappedPath, i);
            double yaw = Math.toDegrees(Math.atan2(direction.getZ(), direction.getX()));

            // Calculate coordinate vectors for 3D placement
            Vector rightVector = new Vector(-direction.getZ(), 0, direction.getX()).normalize();
            Vector upVector = new Vector(0, 1, 0);

            // Select slice index based on cumulative distance to ensure unique slice per point
            int sliceIndex = (int) cumulativeDistance;

            if (sliceIndex >= 0 && sliceIndex < preset.getSlices().size()) {
                WallPreset.WallSlice slice = preset.getSlices().get(sliceIndex);

                // Place blocks for this single slice only
                for (int z = preset.getMinZ(); z <= preset.getMaxZ(); z++) {
                    for (int y = preset.getMinY(); y <= preset.getMaxY(); y++) {
                        String blockDataString = slice.getBlockDataStringRelativeToAxis(
                            z, y, preset.getAxisZOffset(), preset.getAxisYOffset());

                        if (blockDataString != null) {
                            // Calculate world position for this block (no X offset needed for single slice)
                            Location blockLocation = pathPoint.clone()
                                .add(rightVector.clone().multiply(z))
                                .add(upVector.clone().multiply(y + yOffset));

                            // Rotate block data to match wall orientation
                            try {
                                String rotatedBlockDataString = StringBlockRotationUtil.rotateBlockDataString(
                                    blockDataString, Math.toRadians(yaw));
                                BlockData rotatedBlockData = Bukkit.createBlockData(rotatedBlockDataString);

                                wallBlocks.put(blockLocation, rotatedBlockData);
                            } catch (IllegalArgumentException e) {
                                // Enhanced error logging for debugging
                                plugin.getLogger().warning("塀ブロック回転エラー - 元データ: " + blockDataString +
                                    ", yaw角度: " + yaw + "度, 位置: " + blockLocation.getBlockX() + "," +
                                    blockLocation.getBlockY() + "," + blockLocation.getBlockZ());

                                try {
                                    BlockData originalBlockData = Bukkit.createBlockData(blockDataString);
                                    wallBlocks.put(blockLocation, originalBlockData);
                                } catch (IllegalArgumentException e2) {
                                    plugin.getLogger().severe("元のブロックデータも無効: " + blockDataString);
                                }
                            }
                        }
                    }
                }
            }

            // Advance cumulative distance for next iteration
            if (i < snappedPath.size() - 1) {
                Location current = snappedPath.get(i);
                Location next = snappedPath.get(i + 1);
                double distance = Math.sqrt(Math.pow(next.getX() - current.getX(), 2) +
                                          Math.pow(next.getZ() - current.getZ(), 2));
                cumulativeDistance += (float) distance;

                // Wrap around pattern length to maintain repetition
                if (cumulativeDistance >= preset.getLengthX()) {
                    cumulativeDistance = cumulativeDistance - preset.getLengthX();
                }
            }
        }

        return wallBlocks;
    }

    /**
     * Calculate average curvature of the path
     * Positive curvature = right turn, Negative curvature = left turn
     */
    private double calculateAverageCurvature(List<Location> path) {
        if (path.size() < 3) {
            return 0.0; // Straight line or too few points
        }

        double totalCurvature = 0.0;
        int validSegments = 0;

        for (int i = 1; i < path.size() - 1; i++) {
            Location prev = path.get(i - 1);
            Location current = path.get(i);
            Location next = path.get(i + 1);

            // Calculate vectors
            Vector v1 = current.toVector().subtract(prev.toVector());
            Vector v2 = next.toVector().subtract(current.toVector());

            double len1 = v1.length();
            double len2 = v2.length();

            if (len1 > 0.01 && len2 > 0.01) { // Avoid division by zero
                v1.normalize();
                v2.normalize();

                // Calculate curvature using cross product (signed curvature)
                Vector cross = v1.crossProduct(v2);
                double curvature = cross.getY(); // Y component gives us signed curvature in horizontal plane

                totalCurvature += curvature;
                validSegments++;
            }
        }

        return validSegments > 0 ? totalCurvature / validSegments : 0.0;
    }

    /**
     * Determine if this is an inner curve based on curvature and offset
     * curvature > 0 = right turn: positive offset is inner, negative offset is outer
     * curvature < 0 = left turn: negative offset is inner, positive offset is outer
     */
    private boolean isInnerCurve(double curvature, double offset) {
        if (Math.abs(curvature) < 0.01) {
            return false; // Essentially straight, no inner/outer distinction
        }

        if (curvature > 0) {
            // Right turn: positive offset is inner curve
            return offset > 0;
        } else {
            // Left turn: negative offset is inner curve
            return offset < 0;
        }
    }

    /**
     * Thin inner curve path to prevent over-dense block placement
     * Ensures no block gaps while reducing point density
     */
    private List<Location> thinInnerCurvePath(List<Location> path) {
        if (path.size() <= 2) {
            return new ArrayList<>(path);
        }

        List<Location> thinnedPath = new ArrayList<>();
        thinnedPath.add(path.get(0)); // Always keep first point

        for (int i = 1; i < path.size() - 1; i++) {
            Location current = path.get(i);
            Location lastAdded = thinnedPath.get(thinnedPath.size() - 1);

            double distance = current.distance(lastAdded);

            // More conservative thinning to prevent gaps
            // Use minimum distance of 1.2 blocks (reduced from 1.5)
            // Maximum gap tolerance of 1.8 blocks (reduced from 2.0)
            if (distance >= 1.2) {
                thinnedPath.add(current);
            } else if (distance >= 1.8) {
                // Force add to prevent gaps larger than 1.8 blocks
                thinnedPath.add(current);
            }
        }

        // Always keep last point with stricter gap prevention
        if (!path.isEmpty()) {
            Location lastPoint = path.get(path.size() - 1);
            Location lastAdded = thinnedPath.get(thinnedPath.size() - 1);

            // Force add last point if gap would be too large, or always add if path is too short
            if (lastAdded.distance(lastPoint) >= 0.5 || thinnedPath.size() == 1) {
                thinnedPath.add(lastPoint);
            }
        }

        return thinnedPath;
    }

    /**
     * Calculate direction vector at given index
     * Enhanced for thinned paths to ensure accurate direction calculation
     */
    private Vector calculateDirectionVector(List<Location> path, int index) {
        if (path.size() < 2) {
            return new Vector(1, 0, 0); // Default direction
        }

        Vector direction;

        if (index == 0) {
            // First point: use direction to next point
            direction = path.get(1).toVector().subtract(path.get(0).toVector());
        } else if (index == path.size() - 1) {
            // Last point: use direction from previous point
            direction = path.get(index).toVector().subtract(path.get(index - 1).toVector());
        } else {
            // Middle point: use average of incoming and outgoing directions for smoother rotation
            Vector incoming = path.get(index).toVector().subtract(path.get(index - 1).toVector());
            Vector outgoing = path.get(index + 1).toVector().subtract(path.get(index).toVector());

            // Normalize both vectors before averaging
            if (incoming.length() > 0.001) incoming.normalize();
            if (outgoing.length() > 0.001) outgoing.normalize();

            direction = incoming.add(outgoing).multiply(0.5);
        }

        // Ensure we have a valid direction vector
        if (direction.length() < 0.001) {
            return new Vector(1, 0, 0); // Fallback to default
        }

        return direction.normalize();
    }
}