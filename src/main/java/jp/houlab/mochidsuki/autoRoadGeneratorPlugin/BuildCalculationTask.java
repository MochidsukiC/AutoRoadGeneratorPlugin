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

        // ステップA：経路の各点におけるローカル座標系を計算
        List<LocalBasis> pathBases = calculatePathBases(path);

        // ステップB：3Dプリセット・スタンプ処理
        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        World world = path.get(0).getWorld();
        if (world == null) {
            Bukkit.getLogger().severe("BuildCalculationTask: World is null for path location.");
            return;
        }
        
        // プリセットの基準点を取得 (軸の始点)
        // presetAxisPathが空の場合はVector(0,0,0)を返す
        Vector presetOrigin = roadPreset.getAxisPath().isEmpty() ? new Vector(0,0,0) : roadPreset.getAxisPath().get(0);

        // 外側のループ：経路上の各点を処理
        for (int i = 0; i < path.size(); i++) {
            Location pathPoint = path.get(i);
            LocalBasis basis = pathBases.get(i);

            // 内側のループ：プリセット内の各ブロックを処理
            for (Map.Entry<Vector, BlockData> entry : roadPreset.getBlocks().entrySet()) {
                // プリセットブロックの、プリセット原点からの相対座標
                Vector relativeToOrigin = entry.getKey().clone().subtract(presetOrigin);
                
                // ローカル座標系を使って相対座標を回転・変換
                Vector rotatedOffset = basis.right().clone().multiply(relativeToOrigin.getX())
                                     .add(basis.up().clone().multiply(relativeToOrigin.getY()))
                                     .add(basis.forward().clone().multiply(relativeToOrigin.getZ()));

                // ワールド座標を計算
                Location worldBlockLocation = pathPoint.clone().add(rotatedOffset);
                
                // ブロック座標に変換してMapに格納 (重複は自動で処理される)
                worldBlocks.put(worldBlockLocation.getBlock().getLocation(), entry.getValue());
            }
        }

        // ステップC：外側から内側へのソートとキューの作成
        Queue<BlockPlacementInfo> placementQueue = sortBlocksByDistance(worldBlocks, path);

        // ステップD：同期タスクへの引き渡し
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
            Vector forward;
            // 進行方向ベクトルの決定
            if (i < path.size() - 1) {
                forward = path.get(i + 1).toVector().subtract(path.get(i).toVector());
            } else if (i > 0) {
                forward = path.get(i).toVector().subtract(path.get(i - 1).toVector());
            } else {
                forward = new Vector(0, 0, 1); // 経路が1点しかない場合
            }
            if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1); // 退化セグメントのフォールバック
            forward.normalize();

            // 水平な進行方向を計算 (Y軸の変動を無視)
            Vector horizontalForward = new Vector(forward.getX(), 0, forward.getZ());
            if (horizontalForward.lengthSquared() < 1e-6) {
                horizontalForward = lastHorizontalForward.clone(); // 垂直な経路では最後の水平方向を維持
            } else {
                horizontalForward.normalize();
                lastHorizontalForward = horizontalForward.clone(); // 次の垂直経路のために保存
            }

            Vector up = new Vector(0, 1, 0); // ワールドの上方向
            Vector right = up.clone().crossProduct(horizontalForward).normalize(); // Up x HorizontalForward
            
            // rightがゼロベクトルになる場合 (upとhorizontalForwardが平行) のフォールバック
            if (right.lengthSquared() < 1e-6) {
                right = new Vector(1, 0, 0); // ワールドの東をデフォルトの右方向とする
                right.normalize();
            }

            // LocalBasisには、水平に補正されたforwardを使用
            bases.add(new LocalBasis(horizontalForward, up, right));
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
