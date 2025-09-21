package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent; // PlayerItemHeldEventをインポート
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 編集モード中のプレイヤーの操作を検知し、道路網の編集を行うリスナーです。
 */
public class RouteEditListener extends BukkitRunnable implements Listener {

    private final AutoRoadGeneratorPluginMain plugin;
    private final RouteCalculator calculator;
    private final RouteVisualizer visualizer;

    public RouteEditListener(AutoRoadGeneratorPluginMain plugin, RouteCalculator calculator, RouteVisualizer visualizer) {
        this.plugin = plugin;
        this.calculator = calculator;
        this.visualizer = visualizer;
    }

    @Override
    public void run() {
        // 編集モードのプレイヤーに対してリアルタイム更新タスクを実行
        for (UUID uuid : plugin.getEditModePlayers()) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player != null && player.isOnline()) {
                runLiveUpdate(player, plugin.getRouteSession(uuid));
            }
        }
    }

    /**
     * リアルタイム更新処理。主にライブドラッグ（ノード移動のプレビュー）を担当します。
     */
    private void runLiveUpdate(Player player, RouteSession session) {
        UUID selectedNodeId = session.getSelectedNodeId();
        if (selectedNodeId == null) return; // ノードが選択されていなければ何もしない

        RouteNode selectedNode = session.getNode(selectedNodeId);
        if (selectedNode == null) return;

        // 視線の先の位置を取得
        RayTraceResult result = player.rayTraceBlocks(100.0);
        if (result == null || result.getHitBlock() == null) return;
        Location previewLocation = result.getHitBlock().getLocation().add(0.5, 0.5, 0.5);

        // プレビュー位置に合わせて、関連するエッジのパスを一時的に更新
        updateConnectedEdges(session, selectedNode, previewLocation);

        // 描画処理をメインスレッドにディスパッチ
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            visualizer.showAll(player, session);
        });
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getEditModePlayers().contains(player.getUniqueId())) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getDisplayName().equals(ChatColor.GOLD + "道路ブラシ (Road Brush)")) return;

        Action action = event.getAction();
        // ブロック同期を防ぐため、イベントをキャンセル
        event.setCancelled(true);

        // 通常の操作
        if (action == Action.LEFT_CLICK_BLOCK) {
            handleLeftClick(player, event.getClickedBlock());
        } else if (action == Action.RIGHT_CLICK_BLOCK) {
            handleRightClick(player, event.getClickedBlock());
        } else if (action == Action.RIGHT_CLICK_AIR) {
            handleAirRightClick(player);
        }
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getEditModePlayers().contains(player.getUniqueId())) return; // 編集モードでなければ何もしない

        ItemStack item = player.getInventory().getItem(event.getPreviousSlot());
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return; // ロードブラシを持っていなければ何もしない
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getDisplayName().equals(ChatColor.GOLD + "道路ブラシ (Road Brush)")) return;

        RouteSession session = plugin.getRouteSession(player.getUniqueId());

        // 接続モード（branchStartNodeIdが設定されている場合）のみモード変更を許可
        if (session.getBranchStartNodeId() != null) {
            EdgeMode currentMode = session.getCurrentEdgeMode();
            EdgeMode newMode;

            // スクロール方向を判定
            if (event.getNewSlot() > event.getPreviousSlot() || (event.getNewSlot() == 0 && event.getPreviousSlot() == 8)) {
                // 右スクロール (または8->0へのラップアラウンド)
                newMode = currentMode.next();
            } else {
                // 左スクロール (または0->8へのラップアラウンド)
                newMode = currentMode.previous();
            }

            session.setCurrentEdgeMode(newMode);
            player.sendMessage(ChatColor.AQUA + "エッジモードを " + newMode.name() + " に変更しました。");
            updateRoute(player, session);
            event.setCancelled(true); // ホットバーの切り替えをキャンセル
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        // 編集モードのプレイヤーでなければ何もしない
        if (!plugin.getEditModePlayers().contains(player.getUniqueId())) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        // ロードブラシを持っていなければ何もしない
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getDisplayName().equals(ChatColor.GOLD + "道路ブラシ (Road Brush)")) return;

        // Shiftキーを押しながら左クリックしている場合
        if (player.isSneaking()) {
            RouteSession session = plugin.getRouteSession(player.getUniqueId());
            Location brokenBlockLocation = event.getBlock().getLocation();
            UUID nearestNodeId = session.findNearestNodeId(brokenBlockLocation);

            // 破壊されようとしているブロックがノードであれば、イベントをキャンセル
            if (nearestNodeId != null) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * 左クリック時のロジック。ノードの選択、選択解除、削除を処理します。
     */
    private void handleLeftClick(Player player, Block clickedBlock) {
        if (clickedBlock == null) return;
        RouteSession session = plugin.getRouteSession(player.getUniqueId());
        UUID nearestNodeId = session.findNearestNodeId(clickedBlock.getLocation());

        if (nearestNodeId == null) return; // ノード以外は無視

        if (player.isSneaking()) {
            // --- ノード削除処理 ---
            RouteNode nodeToRemove = session.getNodes().remove(nearestNodeId);
            if (nodeToRemove != null) {
                // 関連するエッジもすべて削除
                session.getEdges().removeIf(edge -> edge.getNode1().equals(nodeToRemove) || edge.getNode2().equals(nodeToRemove));
                player.sendMessage(ChatColor.YELLOW + "ノードを削除しました。");
                // 削除されたノードの元のブロックをプレイヤーに送信して復元
                plugin.getServer().getScheduler().runTaskLater(plugin,()->player.sendBlockChange(nodeToRemove.getLocation(), nodeToRemove.getLocation().getBlock().getBlockData()),1L);
                updateRoute(player, session);
            }
        } else {
            // --- ノード選択/選択解除処理 ---
            if (nearestNodeId.equals(session.getSelectedNodeId())) {
                session.setSelectedNodeId(null); // 選択解除
                player.sendMessage(ChatColor.GRAY + "ノードの選択を解除しました。");
                updateRoute(player, session); // パスを元の状態に戻す
            } else {
                session.setSelectedNodeId(nearestNodeId); // 選択
                player.sendMessage(ChatColor.GREEN + "ノードを選択しました。右クリックで移動先を確定します。");
                updateRoute(player, session); // マーカーの表示更新
            }
        }
    }

    /**
     * 右クリック時のロジック。ノードの新規作成、分岐、移動確定を処理します。
     */
    private void handleRightClick(Player player, Block clickedBlock) {
        if (clickedBlock == null) return;
        RouteSession session = plugin.getRouteSession(player.getUniqueId());
        Location clickedLocation = clickedBlock.getLocation();
        UUID selectedNodeId = session.getSelectedNodeId(); // ノード移動用

        // --- ノード移動の確定 ---
        if (selectedNodeId != null) {
            RouteNode nodeToMove = session.getNode(selectedNodeId);
            if (nodeToMove != null) {
                nodeToMove.setLocation(clickedLocation.clone().add(0.5, 0.5, 0.5));
                session.setSelectedNodeId(null); // 選択解除
                player.sendMessage(ChatColor.GREEN + "ノードを移動しました。");
                updateRoute(player, session);
            }
            return;
        }

        // --- 以下、ノードの追加・接続処理 ---
        UUID branchStartNodeId = session.getBranchStartNodeId();
        UUID nearestNodeId = session.findNearestNodeId(clickedLocation); // 今回右クリックされたノード

        if (branchStartNodeId == null) {
            // 分岐始点がまだ選択されていない場合 (1回目の右クリック)
            if (nearestNodeId != null) {
                // 既存のノードを右クリック -> 分岐始点として選択
                session.setBranchStartNodeId(nearestNodeId);
                player.sendMessage(ChatColor.AQUA + "分岐の始点を選択しました。次のノードを右クリックして接続してください。");
                updateRoute(player, session); // マーカーの表示更新
            } else {
                // 空の空間を右クリック -> 新規ノードを作成
                RouteNode newNode = new RouteNode(clickedLocation.clone().add(0.5, 0.5, 0.5));
                session.addNode(newNode);
                player.sendMessage(ChatColor.GREEN + (session.getNodes().size() == 1 ? "最初のノード" : "新しいノード") + "を作成しました。");
                updateRoute(player, session); // マーカーの表示更新
            }
        } else {
            // 分岐始点が既に選択されている場合 (2回目の右クリック)
            RouteNode startNode = session.getNode(branchStartNodeId);
            RouteNode endNode = (nearestNodeId != null) ? session.getNode(nearestNodeId) : new RouteNode(clickedLocation.clone().add(0.5, 0.5, 0.5));
            
            if (nearestNodeId == null) session.addNode(endNode);

            if (startNode != null && !startNode.equals(endNode)) {
                // 既存のエッジをチェックし、存在すれば削除
                RouteEdge existingEdge = null;
                for (RouteEdge edge : session.getEdges()) {
                    // 既存のエッジが (startNode, endNode) または (endNode, startNode) のいずれかであるかを確認
                    if ((edge.getNode1().equals(startNode) && edge.getNode2().equals(endNode)) ||
                        (edge.getNode1().equals(endNode) && edge.getNode2().equals(startNode))) {
                        existingEdge = edge;
                        break;
                    }
                }

                if (existingEdge != null) {
                    session.getEdges().remove(existingEdge);
                    player.sendMessage(ChatColor.YELLOW + "既存の接続を削除しました。");
                }else {
                    // 新しいエッジを作成する際に、現在のエッジモードを適用
                    session.addEdge(new RouteEdge(startNode, endNode, session.getCurrentEdgeMode()));
                    player.sendMessage(ChatColor.GREEN + "ノードを接続しました。モード: " + session.getCurrentEdgeMode().name());

                }

            } else {
                player.sendMessage(ChatColor.RED + "同じノードには接続できません。");
            }
            session.setBranchStartNodeId(null); // 接続が完了したので分岐始点をリセット
            updateRoute(player, session);
        }
    }

    private void handleAirRightClick(Player player) {
        RouteSession session = plugin.getRouteSession(player.getUniqueId());
        if (session.getEdges().isEmpty()) return;

        RayTraceResult result = player.rayTraceBlocks(100.0, org.bukkit.FluidCollisionMode.NEVER);
        Location targetLocation = (result != null && result.getHitBlock() != null) 
                                ? result.getHitPosition().toLocation(player.getWorld()) 
                                : player.getEyeLocation().add(player.getLocation().getDirection().multiply(10));

        RouteEdge closestEdge = null;
        double minDistance = Double.MAX_VALUE;

        for (RouteEdge edge : session.getEdges()) {
            if (edge.getCalculatedPath() == null) continue;
            for (Location point : edge.getCalculatedPath()) {
                double distance = point.distance(targetLocation);
                if (distance < minDistance) {
                    minDistance = distance;
                    closestEdge = edge;
                }
            }
        }

        if (closestEdge != null && minDistance < 2.0) {
            session.getEdges().remove(closestEdge);
            RouteNode newNode = new RouteNode(targetLocation);
            session.addNode(newNode);
            // 新しいエッジを作成する際に、既存のエッジのEdgeModeを引き継ぐ
            session.addEdge(new RouteEdge(closestEdge.getNode1(), newNode, closestEdge.getEdgeMode()));
            session.addEdge(new RouteEdge(newNode, closestEdge.getNode2(), closestEdge.getEdgeMode()));
            player.sendMessage(ChatColor.AQUA + "経路上に新しい中継点を追加しました。");
            updateRoute(player, session);
        }
    }

    /**
     * 道路網の計算と描画を更新します。
     */
    public void updateRoute(Player player, RouteSession session) {
        for (RouteEdge edge : session.getEdges()) {
            updateSingleEdge(session, edge);
        }
        visualizer.showAll(player, session);
    }

    /**
     * 接続されているエッジのパスを一時的に更新します。
     * 主にライブドラッグで使用されます。
     */
    private void updateConnectedEdges(RouteSession session, RouteNode node, Location newLocation) {
        List<RouteEdge> connectedEdges = session.getEdgesConnectedToNode(node);
        for (RouteEdge edge : connectedEdges) {
            Location p1Override = null;
            Location p2Override = null;

            if (edge.getNode1().equals(node)) {
                p1Override = newLocation;
            }
            if (edge.getNode2().equals(node)) {
                p2Override = newLocation;
            }
            edge.setCalculatedPath(calculator.calculate(edge, session, 0.1, p1Override, p2Override));
        }
    }

    /**
     * 単一のエッジのパスを計算し、設定します。
     */
    private void updateSingleEdge(RouteSession session, RouteEdge edge) {
        edge.setCalculatedPath(calculator.calculate(edge, session, 0.1, null, null));
    }
}
