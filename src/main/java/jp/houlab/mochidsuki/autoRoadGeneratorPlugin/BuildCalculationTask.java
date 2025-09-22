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
        if (path == null || path.size() < 2) {
            Bukkit.getLogger().warning("BuildCalculationTask: Path is too short to build for player " + playerUUID);
            // You might want to send an error message to the player here
            return;
        }

        // 向きが安定したローカル座標系を計算 (このメソッドは変更なし)
        List<LocalBasis> pathBases = calculatePathBases(path);

        Map<Location, BlockData> worldBlocks = new ConcurrentHashMap<>();
        World world = path.get(0).getWorld();
        if (world == null) {
            Bukkit.getLogger().severe("BuildCalculationTask: World is null for path location.");
            return;
        }

        // プリセットの原点（基準点）は軸の始点
        Vector presetOrigin = roadPreset.getAxisPath().isEmpty() ? new Vector(0,0,0) : roadPreset.getAxisPath().get(0);

        // ★★★★★★ ここからが修正箇所 ★★★★★★

        // ステップ1：プリセットをZ座標でグループ化し、「正規化された断面リスト」を作成する
        Map<Integer, List<Map.Entry<Vector, BlockData>>> presetSlicesByZ = roadPreset.getBlocks().entrySet().stream()
                .collect(Collectors.groupingBy(entry -> entry.getKey().clone().subtract(presetOrigin).getBlockZ()));

        // Z座標でキーをソートし、インデックス 0, 1, 2... でアクセスできるリストに変換する
        List<List<Map.Entry<Vector, BlockData>>> sortedSlices = presetSlicesByZ.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        if (sortedSlices.isEmpty()) {
            Bukkit.getLogger().warning("BuildCalculationTask: Preset has no blocks to place.");
            return;
        }

        // ステップ2：経路のセグメントごとに、正規化された断面を順番に押し出す
        for (int i = 0; i < path.size() - 1; i++) {
            Location startPoint = path.get(i);
            Location endPoint = path.get(i + 1);
            LocalBasis startBasis = pathBases.get(i);
            LocalBasis endBasis = pathBases.get(i + 1);

            // ★★★★★★ 断面取得ロジックを修正 ★★★★★★
            // プリセットの長さを超えた場合は、%演算子でプリセットの断面を繰り返す
            List<Map.Entry<Vector, BlockData>> slice = sortedSlices.get(i % sortedSlices.size());

            // ステップ3：断面内の各ブロックに対して、始点から終点まで線を引く
            for (Map.Entry<Vector, BlockData> blockEntry : slice) {
                Vector relativePos = blockEntry.getKey().clone().subtract(presetOrigin);
                BlockData blockData = blockEntry.getValue();

                // 始点でのブロックのワールド座標を計算
                Vector startOffset = startBasis.right().clone().multiply(relativePos.getX())
                                   .add(startBasis.up().clone().multiply(relativePos.getY()));
                Location startBlockLoc = startPoint.clone().add(startOffset);

                // 終点でのブロックのワールド座標を計算
                Vector endOffset = endBasis.right().clone().multiply(relativePos.getX())
                                 .add(endBasis.up().clone().multiply(relativePos.getY()));
                Location endBlockLoc = endPoint.clone().add(endOffset);

                // ステップ4：3次元ブレゼンハムアルゴリズムで2点間をブロックで充填
                fillLine3D(startBlockLoc, endBlockLoc, blockData, worldBlocks);
            }
        }

        // 既存のソート処理と設置タスクへの引き渡し (変更なし)
        Queue<BlockPlacementInfo> placementQueue = sortBlocksByDistance(worldBlocks, path);
        new BuildPlacementTask(plugin, playerUUID, placementQueue).runTask(plugin);
    }

    /**
     * 経路上の各点における、向きが安定したローカル座標系（基底）を計算します。
     * 直前の座標系と比較することで、急なカーブでの左右の反転を防ぎます。
     */
    private List<LocalBasis> calculatePathBases(List<Location> path) {
        List<LocalBasis> bases = new ArrayList<>();
        if (path.isEmpty()) {
            return bases;
        }

        Vector lastRight = null; // 直前の「右方向」ベクトルを記憶

        for (int i = 0; i < path.size(); i++) {
            // --- 進行方向 (Forward) ベクトルの計算 ---
            Vector forward;
            if (i < path.size() - 1) {
                forward = path.get(i + 1).toVector().subtract(path.get(i).toVector());
            } else if (i > 0) {
                forward = path.get(i).toVector().subtract(path.get(i - 1).toVector());
            } else {
                forward = new Vector(0, 0, 1); // 点が1つしかない場合のデフォルト
            }
            if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1);
            forward.normalize();

            // --- ローカル座標系の計算 ---
            Vector up = new Vector(0, 1, 0);
            Vector right = up.clone().crossProduct(forward);

            // 水平でない経路(上下移動)の場合、rightがゼロベクトルになる可能性があるためフォールバック
            if (right.lengthSquared() < 1e-6) {
                // 前方ベクトルがY軸に平行な場合、ワールドのX軸を仮の「右」とする
                right = new Vector(1, 0, 0);
            }
            right.normalize();

            // --- ★左右反転防止ロジック★ ---
            if (lastRight != null) {
                // 現在の「右」ベクトルが、直前の「右」ベクトルと逆を向いているかチェック
                if (right.dot(lastRight) < 0) {
                    // 逆を向いている場合、反転させて向きを維持する
                    right.multiply(-1);
                }
            }
            lastRight = right.clone(); // 現在の「右」を次の計算のために記憶

            // 最終的な進行方向は、安定したRightとUpから再計算して直交性を保証する
            Vector finalForward = right.clone().crossProduct(up).normalize();

            bases.add(new LocalBasis(finalForward, up, right));
        }
        return bases;
    }

    /**
     * 3次元ブレゼンハムアルゴリズムを用いて、2点間をブロックで充填します。
     * 結果はworldBlocksマップに直接追加されます。
     */
    private void fillLine3D(Location start, Location end, BlockData data, Map<Location, BlockData> worldBlocks) {
        int x1 = start.getBlockX(), y1 = start.getBlockY(), z1 = start.getBlockZ();
        int x2 = end.getBlockX(), y2 = end.getBlockY(), z2 = end.getBlockZ();

        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);

        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;

        if (dx >= dy && dx >= dz) { // X軸が最も変化が大きい場合
            int err1 = 2 * dy - dx;
            int err2 = 2 * dz - dx;
            while (x1 != x2) {
                worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
                if (err1 > 0) { y1 += sy; err1 -= 2 * dx; }
                if (err2 > 0) { z1 += sz; err2 -= 2 * dx; }
                err1 += 2 * dy;
                err2 += 2 * dz;
                x1 += sx;
            }
        } else if (dy >= dx && dy >= dz) { // Y軸が最も変化が大きい場合
            int err1 = 2 * dx - dy;
            int err2 = 2 * dz - dy;
            while (y1 != y2) {
                worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
                if (err1 > 0) { x1 += sx; err1 -= 2 * dy; }
                if (err2 > 0) { z1 += sz; err2 -= 2 * dy; }
                err1 += 2 * dx;
                err2 += 2 * dz;
                y1 += sy;
            }
        } else { // Z軸が最も変化が大きい場合
            int err1 = 2 * dx - dz;
            int err2 = 2 * dy - dz;
            while (z1 != z2) {
                worldBlocks.put(new Location(start.getWorld(), x1, y1, z1), data);
                if (err1 > 0) { x1 += sx; err1 -= 2 * dz; }
                if (err2 > 0) { y1 += sy; err2 -= 2 * dz; }
                err1 += 2 * dx;
                err2 += 2 * dy;
                z1 += sz;
            }
        }
        // 最後の点を追加
        worldBlocks.put(new Location(start.getWorld(), x2, y2, z2), data);
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
}
