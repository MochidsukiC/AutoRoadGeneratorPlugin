package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class BuildCalculationTask extends BukkitRunnable {

    private final AutoRoadGeneratorPluginMain plugin;
    private final UUID playerUUID;
    private final RouteSession routeSession;
    private final RoadPreset roadPreset;

    /**
     * A helper record to store the calculated local coordinate system for a point on the path.
     */
    private record LocalBasis(Vector forward, Vector up, Vector right) {}

    /**
     * A helper record to store pre-processed preset block information.
     * @param offsetFromAxis The block's position relative to the nearest point on the preset's central axis.
     * @param distanceAlongPresetAxis The distance (index) along the preset's central axis where the nearest point is found.
     * @param data The BlockData for this block.
     */
    private record ProcessedPresetBlock(Vector offsetFromAxis, double distanceAlongPresetAxis, BlockData data) {}

    public BuildCalculationTask(AutoRoadGeneratorPluginMain plugin, UUID playerUUID, RouteSession routeSession, RoadPreset roadPreset, PresetManager presetManager) {
        this.plugin = plugin;
        this.playerUUID = playerUUID;
        this.routeSession = routeSession;
        this.roadPreset = roadPreset;
    }

    @Override
    public void run() {
        List<Location> path = routeSession.getCalculatedPath();
        if (path == null || path.isEmpty()) {
            Bukkit.getLogger().warning("BuildCalculationTask: Path is empty for player " + playerUUID);
            return;
        }

        // ### ステップA：経路の各点におけるローカル座標系の事前計算 ###
        List<LocalBasis> pathBases = calculatePathBases(path);

        // ### 新しいステップ：プリセットのブロックを「中心軸からのオフセット」に変換 ###
        List<ProcessedPresetBlock> processedPresetBlocks = preprocessPresetBlocks(roadPreset, path.get(0).getWorld());

        // ### ステップB：道路経路へのマッピングと体積充填 ###
        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        World world = path.get(0).getWorld();
        if (world == null) {
            Bukkit.getLogger().severe("BuildCalculationTask: World is null for path location.");
            return;
        }

        // 経路の総距離を計算 (ProcessedPresetBlockのdistanceAlongPresetAxisを正規化するため)
        double totalPathLength = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            totalPathLength += path.get(i).distance(path.get(i + 1));
        }

        for (ProcessedPresetBlock processedBlock : processedPresetBlocks) {
            Vector offsetFromAxis = processedBlock.offsetFromAxis();
            double distanceAlongPresetAxis = processedBlock.distanceAlongPresetAxis();
            BlockData blockData = processedBlock.data();

            // プリセット軸上の距離を、計算済み経路のインデックスにマッピング
            // プリセット軸の長さと計算済み経路の長さの比率を考慮してスケーリング
            double scaledDistanceAlongPath = (totalPathLength > 0) ? (distanceAlongPresetAxis / totalPathLength) * (path.size() - 1) : 0;

            int targetPathIndex = (int) Math.floor(scaledDistanceAlongPath);
            double interpolationFactor = scaledDistanceAlongPath - targetPathIndex;

            // 経路の端点処理
            if (targetPathIndex >= path.size() - 1) {
                targetPathIndex = path.size() - 1;
                interpolationFactor = 0;
            }
            if (targetPathIndex < 0) {
                targetPathIndex = 0;
                interpolationFactor = 0;
            }

            Location p1 = path.get(targetPathIndex);
            Location p2 = (targetPathIndex + 1 < path.size()) ? path.get(targetPathIndex + 1) : p1; // 最後の点の場合はP1=P2
            LocalBasis basis1 = pathBases.get(targetPathIndex);
            LocalBasis basis2 = (targetPathIndex + 1 < path.size()) ? pathBases.get(targetPathIndex + 1) : basis1; // 最後の点の場合はBasis1=Basis2

            // 位置と基底を線形補間
            Location interpolatedPathLoc = lerp(p1, p2, interpolationFactor);
            Vector interpolatedRight = lerp(basis1.right, basis2.right, interpolationFactor).normalize();
            Vector interpolatedUp = lerp(basis1.up, basis2.up, interpolationFactor).normalize();
            Vector interpolatedForward = lerp(basis1.forward, basis2.forward, interpolationFactor).normalize();

            // rotated_vec = Right_i.clone().multiply(offsetX) + Up_i.clone().multiply(offsetY) + Forward_i.clone().multiply(offsetZ)
            Vector rotatedOffset = interpolatedRight.clone().multiply(offsetFromAxis.getX())
                                 .add(interpolatedUp.clone().multiply(offsetFromAxis.getY()))
                                 .add(interpolatedForward.clone().multiply(offsetFromAxis.getZ()));

            // 最終的なワールド座標 WorldPos を計算
            Vector worldBlockVector = interpolatedPathLoc.toVector().add(rotatedOffset).toBlockVector();
            Location worldBlockLocation = new Location(world, worldBlockVector.getX(), worldBlockVector.getY(), worldBlockVector.getZ());
            
            // 計算した WorldPos とブロックデータを Map<Location, BlockData> に格納
            // Mapを使うことで座標の重複は自動で処理され、体積充填される
            worldBlocks.put(worldBlockLocation, blockData);
        }

        // ### ステップC：外側から内側への設置順ソート ###
        Queue<BlockPlacementInfo> placementQueue = sortBlocksByDistance(worldBlocks, path);

        // ### 3. 同期タスクへの引き渡し ###
        new BuildPlacementTask(plugin, playerUUID, placementQueue).runTask(plugin);
    }

    /**
     * Calculates the local coordinate system (basis) for each point along the path.
     */
    private List<LocalBasis> calculatePathBases(List<Location> path) {
        List<LocalBasis> bases = new ArrayList<>();
        Vector lastHorizontalForward = new Vector(0, 0, 1); // Default horizontal forward direction (North)

        for (int i = 0; i < path.size(); i++) {
            Vector actual3DForward; // This will be the 'forward' in LocalBasis
            Vector tempHorizontalForward; // Used for calculating 'right' to ensure horizontal orientation

            if (i < path.size() - 1) {
                // 次の点への方向
                actual3DForward = path.get(i + 1).toVector().subtract(path.get(i).toVector());
            } else if (i > 0) {
                // 最後の点の場合、前のセグメントの方向を流用
                actual3DForward = path.get(i).toVector().subtract(path.get(i - 1).toVector());
            } else {
                // 経路が1点しかない場合
                actual3DForward = new Vector(0, 0, 1); // デフォルトの3D方向 (北)
            }

            // Normalize actual3DForward if it's not a zero vector
            if (actual3DForward.lengthSquared() < 1e-6) {
                actual3DForward = new Vector(0, 0, 1); // Fallback for degenerate segment
            } else {
                actual3DForward.normalize();
            }

            // Calculate horizontal component for 'right' vector calculation
            tempHorizontalForward = new Vector(actual3DForward.getX(), 0, actual3DForward.getZ());
            if (tempHorizontalForward.lengthSquared() < 1e-6) {
                // Path is vertical, reuse the last valid horizontal forward direction
                tempHorizontalForward = lastHorizontalForward.clone();
            } else {
                tempHorizontalForward.normalize();
                lastHorizontalForward = tempHorizontalForward.clone(); // Save for next vertical segment
            }

            Vector up = new Vector(0, 1, 0); // ワールドの上方向
            Vector right = up.clone().crossProduct(tempHorizontalForward).normalize(); // Up x HorizontalForward

            // Fallback for right if it becomes a zero vector (e.g., up and tempHorizontalForward are parallel)
            if (right.lengthSquared() < 1e-6) {
                right = new Vector(1, 0, 0); // Default to World's East
                right.normalize();
            }

            // LocalBasisには実際の3D進行方向を格納
            bases.add(new LocalBasis(actual3DForward, up, right));
        }
        return bases;
    }

    /**
     * Pre-processes the preset blocks to store their offset from the nearest point on the preset's central axis.
     * This also determines the distance along the preset axis for each block.
     */
    private List<ProcessedPresetBlock> preprocessPresetBlocks(RoadPreset roadPreset, World world) {
        List<ProcessedPresetBlock> processedBlocks = new ArrayList<>();
        Map<Vector, BlockData> presetBlocks = roadPreset.getBlocks();
        List<Vector> presetAxisPath = roadPreset.getAxisPath();

        if (presetAxisPath.isEmpty()) {
            // If preset has no axis path, treat all blocks as offset from (0,0,0) at axis start
            for (Map.Entry<Vector, BlockData> entry : presetBlocks.entrySet()) {
                processedBlocks.add(new ProcessedPresetBlock(entry.getKey(), 0.0, entry.getValue()));
            }
            return processedBlocks;
        }

        // Calculate cumulative distances along the preset axis path
        List<Double> cumulativeDistances = new ArrayList<>();
        cumulativeDistances.add(0.0);
        for (int i = 0; i < presetAxisPath.size() - 1; i++) {
            double segmentLength = presetAxisPath.get(i).distance(presetAxisPath.get(i + 1));
            cumulativeDistances.add(cumulativeDistances.get(i) + segmentLength);
        }

        for (Map.Entry<Vector, BlockData> entry : presetBlocks.entrySet()) {
            Vector relativePresetBlockPos = entry.getKey();
            BlockData blockData = entry.getValue();

            Vector nearestPointOnPresetAxis = null;
            double minDistanceSquared = Double.MAX_VALUE;
            double distanceAlongPresetAxis = 0.0; // Scaled distance along the axis

            // Find the nearest point on the preset axis path for the current block
            for (int j = 0; j < presetAxisPath.size(); j++) {
                Vector axisPoint = presetAxisPath.get(j);
                double distSq = relativePresetBlockPos.distanceSquared(axisPoint);

                if (distSq < minDistanceSquared) {
                    minDistanceSquared = distSq;
                    nearestPointOnPresetAxis = axisPoint;
                    distanceAlongPresetAxis = cumulativeDistances.get(j);
                }
            }

            if (nearestPointOnPresetAxis != null) {
                Vector offsetFromAxis = relativePresetBlockPos.clone().subtract(nearestPointOnPresetAxis);
                processedBlocks.add(new ProcessedPresetBlock(offsetFromAxis, distanceAlongPresetAxis, blockData));
            } else {
                // Fallback if no axis point found (should not happen with non-empty axisPath)
                processedBlocks.add(new ProcessedPresetBlock(relativePresetBlockPos, 0.0, blockData));
            }
        }
        return processedBlocks;
    }

    /**
     * Sorts the calculated blocks from the outside layers to the inside.
     */
    private Queue<BlockPlacementInfo> sortBlocksByDistance(Map<Location, BlockData> worldBlocks, List<Location> path) {
        TreeMap<Integer, List<BlockPlacementInfo>> sortedBlocksByDistance = new TreeMap<>();
        List<Vector> pathVectors = path.stream().map(Location::toVector).collect(Collectors.toList());

        for (Map.Entry<Location, BlockData> entry : worldBlocks.entrySet()) {
            Location blockLoc = entry.getKey();
            BlockData blockData = entry.getValue();

            double minDistanceSquared = Double.MAX_VALUE;
            for (Vector axisPoint : pathVectors) {
                // Calculate horizontal distance (2D distance on XZ plane)
                double dx = blockLoc.getX() - axisPoint.getX();
                double dz = blockLoc.getZ() - axisPoint.getZ();
                minDistanceSquared = Math.min(minDistanceSquared, dx * dx + dz * dz);
            }
            int distance = (int) Math.round(Math.sqrt(minDistanceSquared));

            sortedBlocksByDistance.computeIfAbsent(distance, k -> new ArrayList<>()).add(new BlockPlacementInfo(blockLoc, blockData));
        }

        Queue<BlockPlacementInfo> placementQueue = new ConcurrentLinkedQueue<>();
        // Add blocks from the farthest distance to the closest (descending order)
        sortedBlocksByDistance.descendingKeySet().forEach(distance -> placementQueue.addAll(sortedBlocksByDistance.get(distance)));

        return placementQueue;
    }

    // Locationの線形補間ヘルパーメソッド
    private Location lerp(Location loc1, Location loc2, double t) {
        if (!loc1.getWorld().equals(loc2.getWorld())) {
            return loc1;
        }
        double x = loc1.getX() * (1 - t) + loc2.getX() * t;
        double y = loc1.getY() * (1 - t) + loc2.getY() * t;
        double z = loc1.getZ() * (1 - t) + loc2.getZ() * t;
        return new Location(loc1.getWorld(), x, y, z);
    }

    // Vectorの線形補間ヘルパーメソッド
    private Vector lerp(Vector vec1, Vector vec2, double t) {
        double x = vec1.getX() * (1 - t) + vec2.getX() * t;
        double y = vec1.getY() * (1 - t) + vec2.getY() * t;
        double z = vec1.getZ() * (1 - t) + vec2.getZ() * t;
        return new Vector(x, y, z);
    }
}
