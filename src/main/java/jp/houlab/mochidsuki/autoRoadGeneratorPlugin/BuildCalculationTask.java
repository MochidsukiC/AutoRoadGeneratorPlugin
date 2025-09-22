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

        List<LocalBasis> pathBases = calculatePathBases(path);

        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        World world = path.get(0).getWorld();
        if (world == null) {
            Bukkit.getLogger().severe("BuildCalculationTask: World is null for path location.");
            return;
        }

        // ステップ1：経路の各点の「始点からの累積距離」を計算する
        List<Double> cumulativeDistances = new ArrayList<>();
        cumulativeDistances.add(0.0);
        for (int i = 0; i < path.size() - 1; i++) {
            double segmentLength = path.get(i).distance(path.get(i + 1));
            cumulativeDistances.add(cumulativeDistances.get(i) + segmentLength);
        }
        double totalPathLength = cumulativeDistances.get(cumulativeDistances.size() - 1);

        // プリセットの基準点を取得 (軸の始点)
        // presetAxisPathが空の場合はVector(0,0,0)を返す
        Vector presetOrigin = roadPreset.getAxisPath().isEmpty() ? new Vector(0,0,0) : roadPreset.getAxisPath().get(0);

        // ステップ2：プリセットの各ブロックを、対応する経路上の位置に配置する (最初のプリセット)
        for (Map.Entry<Vector, BlockData> entry : roadPreset.getBlocks().entrySet()) {
            Vector localPos = entry.getKey(); // (px, py, pz)
            BlockData blockData = entry.getValue();

            // プリセット内の進行距離 = pz
            double presetDistance = localPos.getZ(); 

            // ステップ3：プリセットの進行距離に最も近い、経路上の点を見つける
            int targetPathIndex = findClosestPathIndex(cumulativeDistances, presetDistance);
            if (targetPathIndex == -1) continue; // 経路外の点は無視

            Location pathPoint = path.get(targetPathIndex);
            LocalBasis basis = pathBases.get(targetPathIndex);

            // プリセット内のXYオフセットを取得
            double px = localPos.getX();
            double py = localPos.getY();

            // ローカル座標系を使ってXYオフセットを回転・変換
            Vector rotatedOffset = basis.right().clone().multiply(px)
                                 .add(basis.up().clone().multiply(py));

            // ワールド座標を計算
            Location worldBlockLocation = pathPoint.clone().add(rotatedOffset);
            
            worldBlocks.put(worldBlockLocation.getBlock().getLocation(), blockData);
        }

        // ステップ4：道路全体をプリセットの繰り返しで充填する
        double presetLength = calculatePresetLength(roadPreset);
        if (presetLength > 0) {
            // 最初のプリセットは既に配置済みなので、presetLengthから開始
            for (double distanceOffset = presetLength; distanceOffset < totalPathLength; distanceOffset += presetLength) {
                for (Map.Entry<Vector, BlockData> entry : roadPreset.getBlocks().entrySet()) {
                    Vector localPos = entry.getKey();
                    BlockData blockData = entry.getValue();

                    double presetDistance = localPos.getZ() + distanceOffset;

                    int targetPathIndex = findClosestPathIndex(cumulativeDistances, presetDistance);
                    if (targetPathIndex == -1) continue; // 経路外の点は無視

                    Location pathPoint = path.get(targetPathIndex);
                    LocalBasis basis = pathBases.get(targetPathIndex);

                    Vector rotatedOffset = basis.right().clone().multiply(localPos.getX())
                                         .add(basis.up().clone().multiply(localPos.getY()));
                    
                    Location worldBlockLocation = pathPoint.clone().add(rotatedOffset);
                    worldBlocks.put(worldBlockLocation.getBlock().getLocation(), blockData);
                }
            }
        }

        // 既存のソート処理と設置タスクへの引き渡し
        Queue<BlockPlacementInfo> placementQueue = sortBlocksByDistance(worldBlocks, path);
        new BuildPlacementTask(plugin, playerUUID, placementQueue).runTask(plugin);
    }

    /**
     * Calculates the local coordinate system (basis) for each point along the path.
     * Forward vector is based on horizontal movement to prevent unintended tilting.
     */
    private List<LocalBasis> calculatePathBases(List<Location> path) {
        List<LocalBasis> bases = new ArrayList<>();
        Vector lastHorizontalForward = new Vector(0, 0, 1); // デフォルトの水平進行方向 (北)

        for (int i = 0; i < path.size(); i++) {
            Vector actual3DForward; // This will be the 'forward' in LocalBasis
            Vector tempHorizontalForward; // Used for calculating 'right' to ensure horizontal orientation

            if (i < path.size() - 1) {
                // 次の点への方向
                actual3DForward = path.get(i + 1).toVector().subtract(path.get(i).toVector());
            } else if (i > 0) {
                // 最後の点の場合、前のセグメントの方向を流用
                actual3DForward = path.get(i).toVector().subtract(path.get(i - 1).toVector());
            }
            else {
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

    /**
     * プリセットの軸の長さを計算します。
     */
    private double calculatePresetLength(RoadPreset preset) {
        List<Vector> axis = preset.getAxisPath();
        if (axis == null || axis.size() < 2) {
            return 0.0;
        }
        // Z座標の最大と最小の差を長さとします
        double minZ = axis.stream().mapToDouble(Vector::getZ).min().orElse(0.0);
        double maxZ = axis.stream().mapToDouble(Vector::getZ).max().orElse(0.0);
        return maxZ - minZ;
    }

    /**
     * 累積距離リストの中から、指定された距離に最も近い点のインデックスを探索します。
     */
    private int findClosestPathIndex(List<Double> cumulativeDistances, double targetDistance) {
        int index = Collections.binarySearch(cumulativeDistances, targetDistance);
        if (index >= 0) {
            return index; // 完全一致
        } else {
            // 挿入ポイントから最も近いインデックスを返す
            int insertionPoint = -(index + 1);
            if (insertionPoint == 0) return 0;
            if (insertionPoint == cumulativeDistances.size()) return cumulativeDistances.size() - 1;
            
            double distToPrev = targetDistance - cumulativeDistances.get(insertionPoint - 1);
            double distToNext = cumulativeDistances.get(insertionPoint) - targetDistance;
            
            return (distToPrev < distToNext) ? (insertionPoint - 1) : insertionPoint;
        }
    }

    // Locationの線形補間ヘルパーメソッド (今回は使用しないが、将来的な拡張のために残す)
    private Location lerp(Location loc1, Location loc2, double t) {
        if (!loc1.getWorld().equals(loc2.getWorld())) {
            return loc1;
        }
        double x = loc1.getX() * (1 - t) + loc2.getX() * t;
        double y = loc1.getY() * (1 - t) + loc2.getY() * t;
        double z = loc1.getZ() * (1 - t) + loc2.getZ() * t;
        return new Location(loc1.getWorld(), x, y, z);
    }

    // Vectorの線形補間ヘルパーメソッド (今回は使用しないが、将来的な拡張のために残す)
    private Vector lerp(Vector vec1, Vector vec2, double t) {
        double x = vec1.getX() * (1 - t) + vec2.getX() * t;
        double y = vec1.getY() * (1 - t) + vec2.getY() * t;
        double z = vec1.getZ() * (1 - t) + vec2.getZ() * t;
        return new Vector(x, y, z);
    }
}
