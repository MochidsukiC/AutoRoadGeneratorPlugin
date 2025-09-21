package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 道路網の可視化を管理するクラスです。
 * パーティクルによるエッジと、ブロックによるノードの表示を担当します。
 */
public class RouteVisualizer {

    private final AutoRoadGeneratorPluginMain plugin;
    private final Map<UUID, BukkitTask> particleTasks = new ConcurrentHashMap<>();
    // アンカーマーカーの位置を管理するマップ
    private final Map<UUID, Location> anchorMarkerLocations = new ConcurrentHashMap<>();

    public RouteVisualizer(AutoRoadGeneratorPluginMain plugin) {
        this.plugin = plugin;
    }

    /**
     * プレイヤーに道路網全体（パーティクルとマーカー）を表示します。
     * @param player  対象プレイヤー
     * @param session 表示するルートセッション
     */
    public void showAll(Player player, RouteSession session) {
        stopParticles(player); // 既存のパーティクルタスクを停止

        // マーカーの更新はメインスレッドで即座に行う
        updateMarkers(player, session);
        updateAnchorMarkers(player, session); // アンカーマーカーも更新

        // パーティクル表示タスクを開始 (非同期)
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !plugin.getEditModePlayers().contains(player.getUniqueId())) {
                    cancel();
                    return;
                }
                // 全てのエッジの計算済みパスに沿ってパーティクルを表示
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (RouteEdge edge : session.getEdges()) {
                        if (edge.getCalculatedPath() != null) {
                            Particle.DustOptions dustOptions = getDustOptionsForEdgeMode(edge.getEdgeMode());
                            for (Location point : edge.getCalculatedPath()) {
                                player.spawnParticle(Particle.REDSTONE, point.clone().add(0, 0.5, 0), 1, 0, 0, 0, 0, dustOptions);
                            }
                        }
                    }
                });
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 4L); // 0.2秒ごと

        particleTasks.put(player.getUniqueId(), task);
    }

    /**
     * プレイヤーのすべての表示（パーティクルとマーカー）を非表示にします。
     * @param player  対象プレイヤー
     * @param session 対象のルートセッション
     */
    public void hideAll(Player player, RouteSession session) {
        stopParticles(player);
        clearMarkers(player, session);
        clearAnchorMarkers(player, session); // アンカーマーカーもクリア
    }

    /**
     * プレイヤーのパーティクル表示タスクを停止します。
     */
    private void stopParticles(Player player) {
        BukkitTask task = particleTasks.remove(player.getUniqueId());
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    /**
     * プレイヤーに表示されているノードマーカーブロックをすべて元の状態に戻します。
     */
    private void clearMarkers(Player player, RouteSession session) {
        if (session != null) {
            // メインスレッドでブロック変更を実行
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Location markerLoc : session.getMarkerLocations().values()) {
                    player.sendBlockChange(markerLoc, markerLoc.getBlock().getBlockData());
                }
                session.getMarkerLocations().clear();
            },0L);
        }
    }

    /**
     * プレイヤーに表示されているアンカーマーカーブロックをすべて元の状態に戻します。
     */
    private void clearAnchorMarkers(Player player, RouteSession session) {
        if (session != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Location markerLoc : anchorMarkerLocations.values()) {
                    player.sendBlockChange(markerLoc, markerLoc.getBlock().getBlockData());
                }
                anchorMarkerLocations.clear();
            });
        }
    }

    /**
     * ノードマーカーブロックの表示を更新します。
     * （通常:金、選択中:ダイヤ、分岐始点:エメラルド）
     */
    public void updateMarkers(Player player, RouteSession session) {
        clearMarkers(player, session); // まず既存のマーカーをクリア

        UUID selectedNodeId = session.getSelectedNodeId();
        UUID branchStartNodeId = session.getBranchStartNodeId();

        // メインスレッドでマーカー更新を実行
        Bukkit.getScheduler().runTask(plugin, () -> {
            // 全てのノードをマーカーとして設置
            for (RouteNode node : session.getNodes().values()) {
                Material markerMaterial;
                if (node.getId().equals(branchStartNodeId)) {
                    markerMaterial = Material.EMERALD_BLOCK; // 分岐始点
                } else if (node.getId().equals(selectedNodeId)) {
                    markerMaterial = Material.DIAMOND_BLOCK; // 選択中
                } else {
                    markerMaterial = Material.GOLD_BLOCK;      // 通常
                }
                Location blockLocation = node.getLocation().getBlock().getLocation();
                player.sendBlockChange(blockLocation, markerMaterial.createBlockData());
                session.getMarkerLocations().put(node.getId(), blockLocation);
            }
        });
    }

    /**
     * アンカーマーカーブロックの表示を更新します。
     * （通常:避雷針、選択中:レッドストーンブロック）
     */
    public void updateAnchorMarkers(Player player, RouteSession session) {
        clearAnchorMarkers(player, session); // まず既存のアンカーマーカーをクリア

        UUID selectedAnchorId = session.getSelectedAnchorId();

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (CurveAnchor anchor : session.getAnchors().values()) {
                Material markerMaterial;
                if (anchor.getId().equals(selectedAnchorId)) {
                    markerMaterial = Material.REDSTONE_BLOCK; // 選択中
                } else {
                    markerMaterial = Material.LIGHTNING_ROD; // 通常
                }
                Location blockLocation = anchor.getLocation().getBlock().getLocation().add(0, 1, 0);
                player.sendBlockChange(blockLocation, markerMaterial.createBlockData());
                anchorMarkerLocations.put(anchor.getId(), blockLocation);
            }
        });
    }

    /**
     * EdgeMode に応じた Particle.DustOptions を返します。
     * @param mode エッジモード
     * @return 対応する DustOptions
     */
    private Particle.DustOptions getDustOptionsForEdgeMode(EdgeMode mode) {
        switch (mode) {
            case STRAIGHT:
                return new Particle.DustOptions(Color.YELLOW, 1.0f);
            case CLOTHOID:
                return new Particle.DustOptions(Color.LIME, 1.0f); // 緑色
            case ARC:
                return new Particle.DustOptions(Color.AQUA, 1.0f); // 水色
            default:
                return new Particle.DustOptions(Color.WHITE, 1.0f); // 未定義の場合は白
        }
    }
}
