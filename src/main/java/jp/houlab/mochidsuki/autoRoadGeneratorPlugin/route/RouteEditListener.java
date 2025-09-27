package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route;

import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.AutoRoadGeneratorPluginMain;
import jp.houlab.mochidsuki.autoRoadGeneratorPlugin.util.PlayerMessageUtil;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
                // アクションバーを更新
                sendActionBar(player, plugin.getRouteSession(uuid));
            }
        }
    }

    /**
     * リアルタイム更新処理。主にライブドラッグ（ノードまたはアンカー移動のプレビュー）を担当します。
     */
    private void runLiveUpdate(Player player, RouteSession session) {
        UUID selectedNodeId = session.getSelectedNodeId();
        UUID selectedAnchorId = session.getSelectedAnchorId();

        if (selectedNodeId == null && selectedAnchorId == null) return; // ノードもアンカーも選択されていなければ何もしない

        // 視線の先の位置を取得
        RayTraceResult result = player.rayTraceBlocks(5.0);
        Location previewLocation = null;
        if (result == null || result.getHitBlock() == null) {
            previewLocation = player.getEyeLocation().add(player.getLocation().getDirection().multiply(5));
        }else {
            previewLocation = result.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
        }

        if (selectedNodeId != null) {
            RouteNode selectedNode = session.getNode(selectedNodeId);
            if (selectedNode == null) return;
            // ノード移動のプレビュー
            updateConnectedEdges(session, selectedNode, previewLocation);
        } else if (selectedAnchorId != null) {
            CurveAnchor selectedAnchor = session.getAnchor(selectedAnchorId);
            if (selectedAnchor == null) return;

            // アンカー編集モードに基づいてpreviewLocationを制約
            Location constrainedPreviewLocation = getConstrainedLocation(
                    previewLocation,
                    session.getCurrentAnchorEditMode(),
                    session.getOriginalSelectedAnchorLocation()
            );
            // アンカー移動のプレビュー
            runLiveUpdateForAnchor(session, selectedAnchor, constrainedPreviewLocation);
        }

        // 描画処理をメインスレッドにディスパッチ
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            visualizer.showAll(player, session);
        });
    }

    /**
     * リアルタイム更新処理。主にライブドラッグ（アンカー移動のプレビュー）を担当します。
     */
    private void runLiveUpdateForAnchor(RouteSession session, CurveAnchor anchor, Location newLocation) {
        RouteEdge edge = session.getEdgeWithAnchor(anchor);
        if (edge == null) return;

        // アンカーのLocationを一時的に上書きしてパスを再計算
        Location originalAnchorLocation = anchor.getLocation();
        anchor.setLocation(newLocation);
        edge.setCalculatedPath(calculator.calculate(edge, session, 0.1, null, null));
        anchor.setLocation(originalAnchorLocation); // 元に戻す
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // メインハンドでのインタラクションのみを処理
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (!plugin.getEditModePlayers().contains(player.getUniqueId())) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "road_brush"), PersistentDataType.STRING)) return;
        if(player.getCooldown(Material.BLAZE_ROD) > 0) return;

        Action action = event.getAction();
        event.setCancelled(true);

        player.setCooldown(Material.BLAZE_ROD,1);

        RouteSession session = plugin.getRouteSession(player.getUniqueId());

        Location interactionLocation = null;
        Block clickedBlock = event.getClickedBlock(); // handleLeftClick用、およびブロック上のノード検索用

        // プレイヤーの視線が当たっている正確な位置（ブロック上または空中）を取得
        if(event.getAction() == Action.RIGHT_CLICK_AIR) {
            RayTraceResult result = player.rayTraceBlocks(5);
            if (result != null && result.getHitBlock() != null) {
                interactionLocation = result.getHitBlock().getLocation();
            }
            if (interactionLocation == null) {
                // RayTraceが何もヒットしなかった場合のフォールバック（空を見上げている場合など）
                interactionLocation = player.getEyeLocation().add(player.getLocation().getDirection().multiply(5));
            }
        }else if(event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            interactionLocation = event.getClickedBlock().getLocation();
        }

        // --- アンカー操作の処理 --- (ノード操作より優先)
        // 視覚的なアンカーブロックの位置から実際のアンカー位置を逆算して検索
        Location anchorCheckLocation = interactionLocation.clone().subtract(0, 1, 0);
        UUID nearestAnchorId = session.findNearestAnchorId(anchorCheckLocation);
        if (nearestAnchorId != null) {
            handleAnchorClick(player, nearestAnchorId, action);
            return; // アンカー操作が処理されたら、それ以上の処理は行わない
        }

        // --- アンカー移動の確定 --- (selectedAnchorIdが設定されている場合)
        UUID selectedAnchorId = session.getSelectedAnchorId();
        if (selectedAnchorId != null && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            CurveAnchor anchorToMove = session.getAnchor(selectedAnchorId);
            if (anchorToMove != null) {
                Location finalAnchorLocation = getConstrainedLocation(
                        interactionLocation,
                        session.getCurrentAnchorEditMode(),
                        session.getOriginalSelectedAnchorLocation()
                );
                anchorToMove.setLocation(finalAnchorLocation);
                session.setSelectedAnchorId(null); // 選択解除
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.anchor_moved");
                updateRoute(player, session);
            }
            return;
        }

        // --- ノード操作の処理 --- (アンカー操作がなかった場合)
        if (action == Action.LEFT_CLICK_BLOCK) {
            handleLeftClick(player, clickedBlock);
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            // 右クリックイベントをノード操作としてまず処理
            boolean handledByNodeOperation = handleRightClick(player, interactionLocation);
            if (!handledByNodeOperation) {
                // ノード操作で処理されなかった場合、エッジ分割として処理
                handleAirRightClick(player, interactionLocation);
            }
        }
    }

    /**
     * アンカークリック時のロジック。アンカーの選択、選択解除、削除を処理します。
     */
    private void handleAnchorClick(Player player, UUID clickedAnchorId, Action action) {
        RouteSession session = plugin.getRouteSession(player.getUniqueId());

        if (player.isSneaking()) {
            // --- アンカー削除処理 --- (Shift + クリック)
            CurveAnchor anchorToRemove = session.getAnchor(clickedAnchorId);
            if (anchorToRemove != null) {
                RouteEdge edge = session.getEdgeWithAnchor(anchorToRemove);
                if (edge != null) {
                    edge.setCurveAnchor(null); // エッジからアンカーを解除
                }
                session.removeAnchor(clickedAnchorId);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.anchor_deleted");
                updateRoute(player, session);
            }
        } else {
            // --- アンカー選択/選択解除処理 --- (通常クリック)
            if (clickedAnchorId.equals(session.getSelectedAnchorId())) {
                session.setSelectedAnchorId(null); // 選択解除
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.anchor_deselected");
                updateRoute(player, session); // マーカーの表示更新
            } else {
                session.setSelectedAnchorId(clickedAnchorId); // 選択
                session.setSelectedNodeId(null); // ノード選択を解除
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.anchor_selected");
                updateRoute(player, session); // マーカーの表示更新
            }
        }
    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getEditModePlayers().contains(player.getUniqueId())) return; // 編集モードでなければ何もしない

        ItemStack item = player.getInventory().getItem(event.getPreviousSlot());
        if (item == null || item.getType() != Material.BLAZE_ROD || !item.hasItemMeta()) return; // ロードブラシを持っていなければ何もしない
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "road_brush"), PersistentDataType.STRING)) return;

        RouteSession session = plugin.getRouteSession(player.getUniqueId());

        // アンカーが選択されている場合、アンカー編集モードを切り替える
        if (session.getSelectedAnchorId() != null) {
            AnchorEditMode currentMode = session.getCurrentAnchorEditMode();
            AnchorEditMode newMode;

            // スクロール方向を判定
            if (event.getNewSlot() > event.getPreviousSlot() || (event.getNewSlot() == 0 && event.getPreviousSlot() == 8)) {
                // 右スクロール (または8->0へのラップアラウンド)
                newMode = currentMode.next();
            } else {
                // 左スクロール (または0->8へのラップアラウンド)
                newMode = currentMode.previous();
            }

            session.setCurrentAnchorEditMode(newMode);
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.anchor_edit_mode_changed", newMode.name());
            sendActionBar(player, session); // アクションバーを更新
            updateRoute(player, session);
            event.setCancelled(true); // ホットバーの切り替えをキャンセル
            return; // アンカーモード切り替えが処理されたら、これ以上は処理しない
        }

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
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.edge_mode_changed", newMode.name());
            sendActionBar(player, session); // アクションバーを更新
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
        if (meta == null || !meta.getPersistentDataContainer().has(new NamespacedKey(plugin, "road_brush"), PersistentDataType.STRING)) return;

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
                session.getEdges().removeIf(edge -> {
                    if (edge.getNode1().equals(nodeToRemove) || edge.getNode2().equals(nodeToRemove)) {
                        if (edge.getCurveAnchor() != null) {
                            session.removeAnchor(edge.getCurveAnchor().getId()); // 関連アンカーも削除
                        }
                        return true;
                    }
                    return false;
                });
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.node_deleted");
                // 削除されたノードの元のブロックをプレイヤーに送信して復元
                plugin.getServer().getScheduler().runTaskLater(plugin,()->player.sendBlockChange(nodeToRemove.getLocation(), nodeToRemove.getLocation().getBlock().getBlockData()),1L);
                updateRoute(player, session);
            }
        } else {
            // --- ノード選択/選択解除処理 ---
            if (nearestNodeId.equals(session.getSelectedNodeId())) {
                session.setSelectedNodeId(null); // 選択解除
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.node_deselected");
                updateRoute(player, session); // パスを元の状態に戻す
            } else {
                session.setSelectedNodeId(nearestNodeId); // 選択
                session.setSelectedAnchorId(null); // アンカー選択を解除
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.node_selected");
                updateRoute(player, session); // マーカーの表示更新
            }
        }
    }

    /**
     * 右クリック時のロジック。ノードの新規作成、分岐、移動確定、アンカー移動確定を処理します。
     * @param player 操作プレイヤー
     * @param interactionLocation プレイヤーがインタラクションした正確な位置 (ブロック上または空中)
     * @return ノード操作が実行された場合はtrue、それ以外はfalse
     */
    private boolean handleRightClick(Player player, Location interactionLocation) {
        RouteSession session = plugin.getRouteSession(player.getUniqueId());
        UUID selectedNodeId = session.getSelectedNodeId(); // ノード移動用

        // --- ノード移動の確定 ---
        if (selectedNodeId != null) {
            RouteNode nodeToMove = session.getNode(selectedNodeId);
            if (nodeToMove != null) {
                // ノードは常にブロックの中心にスナップ
                nodeToMove.setLocation(interactionLocation.getBlock().getLocation().add(0.5, 0.5, 0.5));
                session.setSelectedNodeId(null); // 選択解除
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.node_moved");
                updateRoute(player, session);
                return true;
            }
        }

        // --- 以下、ノードの追加・接続処理 ---
        UUID branchStartNodeId = session.getBranchStartNodeId();
        // ノード検索はブロック位置で行う
        UUID nearestNodeId = session.findNearestNodeId(interactionLocation.getBlock().getLocation());

        if (branchStartNodeId == null) {
            // 分岐始点がまだ選択されていない場合 (1回目の右クリック)
            if (nearestNodeId != null) {
                // 既存のノードを右クリック -> 分岐始点として選択
                session.setBranchStartNodeId(nearestNodeId);
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.branch_start_selected");
                sendActionBar(player, session); // 接続モードに入ったのでアクションバー表示
                updateRoute(player, session); // マーカーの表示更新
                return true;
            } else {
                // 空の空間を右クリック -> 新規ノードを作成
                // ノードは常にブロックの中心にスナップ
                RouteNode newNode = new RouteNode(interactionLocation.getBlock().getLocation().add(0.5, 0.5, 0.5));
                session.addNode(newNode);
                String nodeType = session.getNodes().size() == 1 ?
                    plugin.getMessageManager().getMessage("route_edit.first_node_created") :
                    plugin.getMessageManager().getMessage("route_edit.new_node_created");
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.node_created", nodeType);
                updateRoute(player, session); // マーカーの表示更新
                return true;
            }
        } else {
            // 分岐始点が既に選択されている場合 (2回目の右クリック)
            RouteNode startNode = session.getNode(branchStartNodeId);
            RouteNode endNode;

            if (nearestNodeId != null) {
                endNode = session.getNode(nearestNodeId);
            } else {
                // 新しい終点ノードを生成（ブロックの中心にスナップ）
                endNode = new RouteNode(interactionLocation.getBlock().getLocation().add(0.5, 0.5, 0.5));
                session.addNode(endNode);
            }

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
                    if (existingEdge.getCurveAnchor() != null) {
                        session.removeAnchor(existingEdge.getCurveAnchor().getId()); // 関連アンカーも削除
                    }
                    PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.existing_connection_deleted");
                } else {
                    RouteEdge newEdge = new RouteEdge(startNode, endNode, session.getCurrentEdgeMode());
                    // ARCまたはCLOTHOIDモードの場合、アンカーを自動生成
                    if (session.getCurrentEdgeMode() == EdgeMode.ARC || session.getCurrentEdgeMode() == EdgeMode.CLOTHOID) {
                        Location midPoint = startNode.getLocation().clone().add(endNode.getLocation()).multiply(0.5);
                        CurveAnchor newAnchor = new CurveAnchor(getAnchorPlacementLocation(midPoint)); // ヘルパーメソッドを使用
                        session.addAnchor(newAnchor);
                        newEdge.setCurveAnchor(newAnchor);
                        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.nodes_connected_with_anchor", session.getCurrentEdgeMode().name());
                    } else {
                        PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.nodes_connected", session.getCurrentEdgeMode().name());
                    }
                    session.addEdge(newEdge);
                }
                session.setBranchStartNodeId(null); // 接続が完了したので分岐始点をリセット
                PlayerMessageUtil.clearActionBar(player); // アクションバーをクリア
                updateRoute(player, session);
                return true;
            } else {
                PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.cannot_connect_same_node");
                session.setBranchStartNodeId(null); // 接続失敗でも分岐始点をリセット
                PlayerMessageUtil.clearActionBar(player); // アクションバーをクリア
                updateRoute(player, session);
                return true; // 接続試行は行われたため、処理済みとみなす
            }
        }
    }

    private void handleAirRightClick(Player player, Location interactionLocation) {
        RouteSession session = plugin.getRouteSession(player.getUniqueId());
        if (session.getEdges().isEmpty()) return;

        // interactionLocationを直接使用
        Location targetLocation = interactionLocation;

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
            // エッジ分割で作成される新しいノードは常にブロックの中心にスナップ
            RouteNode newNode = new RouteNode(targetLocation.getBlock().getLocation().add(0.5, 0.5, 0.5));
            session.addNode(newNode);

            RouteEdge newEdge1 = new RouteEdge(closestEdge.getNode1(), newNode, closestEdge.getEdgeMode());
            RouteEdge newEdge2 = new RouteEdge(newNode, closestEdge.getNode2(), closestEdge.getEdgeMode());

            if (closestEdge.getCurveAnchor() != null) {
                newEdge1.setCurveAnchor(closestEdge.getCurveAnchor());
            }

            session.addEdge(newEdge1);
            session.addEdge(newEdge2);
            PlayerMessageUtil.sendTranslatedMessage(plugin, player, "route_edit.waypoint_added");
            updateRoute(player, session);
        }
    }

    /**
     * 道路網の計算と描画を更新します。
     */
    public void updateRoute(Player player, RouteSession session) {
        // 個々のエッジのパスを更新
        for (RouteEdge edge : session.getEdges()) {
            updateSingleEdge(session, edge);
        }

        // すべてのエッジのパスを結合して、RouteSessionのcalculatedPathに設定
        LinkedHashSet<Location> combinedPath = new LinkedHashSet<>(); // 重複を避けるためにLinkedHashSetを使用
        for (RouteEdge edge : session.getEdges()) {
            if (edge.getCalculatedPath() != null) {
                combinedPath.addAll(edge.getCalculatedPath());
            }
        }
        session.setCalculatedPath(new ArrayList<>(combinedPath));

        visualizer.showAll(player, session);
        // ルート更新時にもアクションバーを更新（特にノード移動時など）
        sendActionBar(player, session);
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

    /**
     * 指定された位置にアンカーを配置する際の最終的なLocationを決定します。
     * 周囲3ブロック内に固形ブロックがない場合は空中に配置し、そうでない場合はブロックの中心に配置します。
     * @param targetLocation プレイヤーがインタラクションした位置
     * @return アンカーが配置されるべき最終的なLocation
     */
    private Location getAnchorPlacementLocation(Location targetLocation) {
        // 周囲3ブロックの範囲に固形ブロックがあるかチェック (7x7x7 cube)
        boolean hasNearbySolidBlocks = false;
        for (int x = -3; x <= 3; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    Block block = targetLocation.clone().add(x, y, z).getBlock();
                    if (block.getType().isSolid()) { // 固形ブロックを検出
                        hasNearbySolidBlocks = true;
                        break;
                    }
                }
                if (hasNearbySolidBlocks) break;
            }
            if (hasNearbySolidBlocks) break;
        }

        if (hasNearbySolidBlocks) {
            // 周囲に固形ブロックがある場合は、クリックされたブロックの中心にスナップ
            return targetLocation.getBlock().getLocation().add(0.5, 0.5, 0.5);
        } else {
            // 周囲に固形ブロックがない場合は、空中にそのまま配置
            return targetLocation;
        }
    }

    /**
     * プレイヤーのアクションバーに現在のモードを表示します。
     * @param player 対象プレイヤー
     * @param session プレイヤーのルートセッション
     */
    private void sendActionBar(Player player, RouteSession session) {
        String message = "";
        if (session.getSelectedAnchorId() != null) {
            // アンカーが選択されている場合、アンカー編集モードを表示
            String modeName = session.getCurrentAnchorEditMode().name();
            message = plugin.getMessageManager().getMessage("route_edit.anchor_edit_mode_display", modeName);
        } else if (session.getBranchStartNodeId() != null) {
            // ノード接続モードの場合、エッジモードを表示
            String modeName = session.getCurrentEdgeMode().name();
            message = plugin.getMessageManager().getMessage("route_edit.current_edge_mode_display", modeName);
        }

        PlayerMessageUtil.sendActionBar(player, message);
    }

    /**
     * 指定されたアンカー編集モードに基づいて、Locationを制約します。
     * @param targetLocation プレイヤーがインタラクションした位置
     * @param mode 現在のアンカー編集モード
     * @param originalLocation アンカーが選択された時の元の位置
     * @return 制約されたLocation
     */
    private Location getConstrainedLocation(Location targetLocation, AnchorEditMode mode, @Nullable Location originalLocation) {
        if (originalLocation == null) {
            return targetLocation; // 元の位置がなければ制約なし
        }

        switch (mode) {
            case FREE:
                return targetLocation;
            case Y_AXIS_FIXED:
                return new Location(
                        targetLocation.getWorld(),
                        targetLocation.getX(),
                        originalLocation.getY(),
                        targetLocation.getZ()
                );
            case Y_AXIS_ONLY:
                return new Location(
                        targetLocation.getWorld(),
                        originalLocation.getX(),
                        targetLocation.getY(),
                        originalLocation.getZ()
                );
            default:
                return targetLocation;
        }
    }
}
