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

        // ### ステップA：経路の各点におけるローカル座標系の事前計算 ###
        List<LocalBasis> pathBases = calculatePathBases(path);

        // ### ステップB：3Dプリセットを経路上の各点にスタンプし、体積充填する ###
        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        World world = path.get(0).getWorld();
        if (world == null) {
            Bukkit.getLogger().severe("BuildCalculationTask: World is null for path location.");
            return;
        }

        // プリセットの基準点からの相対座標リスト
        Map<Vector, BlockData> presetBlocks = roadPreset.getBlocks();
        List<Vector> presetAxisPath = roadPreset.getAxisPath();

        // プリセットの軸の最初の点を基準として、プリセットの軸の相対座標をワールド座標に変換するためのオフセットを計算
        Vector presetAxisStartRelative = presetAxisPath.isEmpty() ? new Vector(0,0,0) : presetAxisPath.get(0);

        // --- ループ構造の変更: 経路上の各点をループ --- 
        for (int i = 0; i < path.size(); i++) {
            Location currentPathLoc = path.get(i);
            LocalBasis currentBasis = pathBases.get(i);

            // --- 内側のループ: roadPresetのblocksマップの各ブロックでループ --- 
            for (Map.Entry<Vector, BlockData> entry : presetBlocks.entrySet()) {
                Vector relativePresetBlockPos = entry.getKey(); // (px, py, pz)
                BlockData blockData = entry.getValue();

                // プリセットの相対座標を、プリセットの軸の開始点が原点になるように調整
                Vector adjustedRelative = relativePresetBlockPos.clone().subtract(presetAxisStartRelative);

                // 3D変換式の導入: adjustedRelative を currentPathLoc のローカル座標系を使って回転・変換
                // rotated_vec = Right_i.clone().multiply(px) + Up_i.clone().multiply(py) + Forward_i.clone().multiply(pz)
                Vector rotatedOffset = currentBasis.right.clone().multiply(adjustedRelative.getX())
                                     .add(currentBasis.up.clone().multiply(adjustedRelative.getY()))
                                     .add(currentBasis.forward.clone().multiply(adjustedRelative.getZ()));

                // 最終的なワールド座標 WorldPos を計算
                Vector worldBlockVector = currentPathLoc.toVector().add(rotatedOffset).toBlockVector();
                Location worldBlockLocation = new Location(world, worldBlockVector.getX(), worldBlockVector.getY(), worldBlockVector.getZ());
                
                // 計算した WorldPos とブロックデータを Map<Location, BlockData> に格納
                // Mapを使うことで座標の重複は自動で処理され、体積充填される
                worldBlocks.put(worldBlockLocation, blockData);
            }
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
