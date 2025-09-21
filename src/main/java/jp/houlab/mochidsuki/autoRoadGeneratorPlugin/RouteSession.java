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
    private volatile UUID branchStartNodeId;
    private volatile Location previewLocation;
    private final Map<UUID, Location> markerLocations = new ConcurrentHashMap<>();
    private volatile UUID selectedNodeId;
    private EdgeMode currentEdgeMode; // 現在選択されているエッジモード

    public RouteSession() {
        this.currentEdgeMode = EdgeMode.STRAIGHT; // デフォルトは直線モード
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

    public void clearSession() {
        nodes.clear();
        edges.clear();
        this.branchStartNodeId = null;
        this.previewLocation = null;
        this.markerLocations.clear();
        this.selectedNodeId = null;
        this.currentEdgeMode = EdgeMode.STRAIGHT; // セッションクリア時もリセット
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

    public EdgeMode getCurrentEdgeMode() {
        return currentEdgeMode;
    }

    public void setCurrentEdgeMode(EdgeMode currentEdgeMode) {
        this.currentEdgeMode = currentEdgeMode;
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

    public List<RouteEdge> getEdgesConnectedToNode(RouteNode node) {
        List<RouteEdge> connectedEdges = new ArrayList<>();
        for (RouteEdge edge : edges) {
            if (edge.getNode1().equals(node) || edge.getNode2().equals(node)) {
                connectedEdges.add(edge);
            }
        }
        return connectedEdges;
    }
}
