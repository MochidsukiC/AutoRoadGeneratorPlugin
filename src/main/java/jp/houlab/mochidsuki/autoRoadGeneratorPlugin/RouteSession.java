package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プレイヤー1人分の編集セッション情報を保持するクラスです。
 * 道路のノードとエッジのグラフ構造を管理します。
 */
public class RouteSession {
    private final Map<UUID, RouteNode> nodes = new ConcurrentHashMap<>();
    private final List<RouteEdge> edges = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, CurveAnchor> anchors = new ConcurrentHashMap<>(); // アンカーを追加
    private volatile UUID branchStartNodeId;
    private volatile Location previewLocation;
    private final Map<UUID, Location> markerLocations = new ConcurrentHashMap<>();
    private volatile UUID selectedNodeId;
    private volatile UUID selectedAnchorId; // 選択中のアンカーIDを追加
    private volatile Location originalSelectedAnchorLocation; // 選択中のアンカーの元の位置
    private EdgeMode currentEdgeMode; // 現在選択されているエッジモード
    private AnchorEditMode currentAnchorEditMode; // 現在選択されているアンカー編集モード
    private List<Location> calculatedPath = new ArrayList<>(); // 追加: 計算された高密度経路

    public RouteSession() {
        this.currentEdgeMode = EdgeMode.STRAIGHT; // デフォルトは直線モード
        this.currentAnchorEditMode = AnchorEditMode.FREE; // デフォルトはフリーモード
    }

    public void addNode(RouteNode node) {
        nodes.put(node.getId(), node);
    }

    public RouteNode getNode(UUID id) {
        return nodes.get(id);
    }

    public Map<UUID, RouteNode> getNodes() {
        return nodes;
    }

    public void addEdge(RouteEdge edge) {
        // 既に同じエッジが存在しないか確認してから追加
        if (!edges.contains(edge)) {
            edges.add(edge);
        }
    }

    public List<RouteEdge> getEdges() {
        return edges;
    }

    public void addAnchor(CurveAnchor anchor) {
        anchors.put(anchor.getId(), anchor);
    }

    public CurveAnchor getAnchor(UUID id) {
        return anchors.get(id);
    }

    public Map<UUID, CurveAnchor> getAnchors() {
        return anchors;
    }

    public void removeAnchor(UUID id) {
        anchors.remove(id);
        if (id.equals(selectedAnchorId)) {
            selectedAnchorId = null;
            originalSelectedAnchorLocation = null; // アンカー削除時もリセット
        }
    }

    public void clearSession() {
        nodes.clear();
        edges.clear();
        anchors.clear(); // アンカーもクリア
        this.branchStartNodeId = null;
        this.previewLocation = null;
        this.markerLocations.clear();
        this.selectedNodeId = null;
        this.selectedAnchorId = null; // 選択中のアンカーもクリア
        this.originalSelectedAnchorLocation = null; // 元の位置もクリア
        this.currentEdgeMode = EdgeMode.STRAIGHT; // セッションクリア時もリセット
        this.currentAnchorEditMode = AnchorEditMode.FREE; // アンカー編集モードもリセット
        this.calculatedPath.clear(); // 追加: 計算された経路もクリア
    }

    public UUID getBranchStartNodeId() {
        return branchStartNodeId;
    }

    public void setBranchStartNodeId(UUID branchStartNodeId) {
        this.branchStartNodeId = branchStartNodeId;
    }

    public Location getPreviewLocation() {
        return previewLocation;
    }

    public void setPreviewLocation(Location previewLocation) {
        this.previewLocation = previewLocation;
    }

    public Map<UUID, Location> getMarkerLocations() {
        return markerLocations;
    }

    public UUID getSelectedNodeId() {
        return selectedNodeId;
    }

    public void setSelectedNodeId(UUID selectedNodeId) {
        this.selectedNodeId = selectedNodeId;
    }

    public UUID getSelectedAnchorId() {
        return selectedAnchorId;
    }

    public void setSelectedAnchorId(UUID selectedAnchorId) {
        this.selectedAnchorId = selectedAnchorId;
        if (selectedAnchorId != null) {
            // アンカーが選択されたらその位置を保存
            CurveAnchor selectedAnchor = getAnchor(selectedAnchorId);
            if (selectedAnchor != null) {
                this.originalSelectedAnchorLocation = selectedAnchor.getLocation().clone();
            }
        } else {
            this.originalSelectedAnchorLocation = null; // 選択解除されたらクリア
        }
    }

    public Location getOriginalSelectedAnchorLocation() {
        return originalSelectedAnchorLocation;
    }

    public EdgeMode getCurrentEdgeMode() {
        return currentEdgeMode;
    }

    public void setCurrentEdgeMode(EdgeMode currentEdgeMode) {
        this.currentEdgeMode = currentEdgeMode;
    }

    public AnchorEditMode getCurrentAnchorEditMode() {
        return currentAnchorEditMode;
    }

    public void setCurrentAnchorEditMode(AnchorEditMode currentAnchorEditMode) {
        this.currentAnchorEditMode = currentAnchorEditMode;
    }

    /**
     * 指定されたブロック位置にノードが存在するかどうかを判定し、そのIDを返します。
     * @param blockLocation 検索するブロックのLocation
     * @return ノードのID、見つからなければnull
     */
    public UUID findNearestNodeId(Location blockLocation) {
        // 比較のために、検索対象の座標をブロック座標（整数）に変換
        Location searchLocation = blockLocation.getBlock().getLocation();
        for (RouteNode node : nodes.values()) {
            // 各ノードの座標もブロック座標に変換して比較
            if (node.getLocation().getBlock().getLocation().equals(searchLocation)) {
                return node.getId(); // ブロック座標が一致すれば、それは同じノードとみなす
            }
        }
        return null; // 範囲内にノードが見つからなかった
    }

    /**
     * 指定されたブロック位置にアンカーが存在するかどうかを判定し、そのIDを返します。
     * @param blockLocation 検索するブロックのLocation
     * @return アンカーのID、見つからなければnull
     */
    public UUID findNearestAnchorId(Location blockLocation) {
        Location searchLocation = blockLocation.getBlock().getLocation();
        for (CurveAnchor anchor : anchors.values()) {
            if (anchor.getLocation().getBlock().getLocation().equals(searchLocation)) {
                return anchor.getId();
            }
        }
        return null;
    }

    public List<RouteEdge> getEdgesConnectedToNode(RouteNode node) {
        List<RouteEdge> connectedEdges = new ArrayList<>();
        for (RouteEdge edge : edges) {
            if (edge.getNode1().equals(node) || edge.getNode2().equals(node)) {
                connectedEdges.add(edge);
            }
        }
        return connectedEdges;
    }

    /**
     * 指定されたアンカーに関連付けられているエッジを返します。
     * @param anchor 検索するアンカー
     * @return アンカーに関連付けられているRouteEdge、見つからなければnull
     */
    public RouteEdge getEdgeWithAnchor(CurveAnchor anchor) {
        for (RouteEdge edge : edges) {
            if (edge.getCurveAnchor() != null && edge.getCurveAnchor().equals(anchor)) {
                return edge;
            }
        }
        return null;
    }

    /**
     * 計算された高密度経路を取得します。
     * @return 計算された高密度経路のリスト
     */
    public List<Location> getCalculatedPath() {
        return calculatedPath;
    }

    /**
     * 計算された高密度経路を設定します。
     * @param calculatedPath 設定する高密度経路のリスト
     */
    public void setCalculatedPath(List<Location> calculatedPath) {
        this.calculatedPath = calculatedPath;
    }
}
