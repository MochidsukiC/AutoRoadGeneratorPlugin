package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;
import java.util.List;
import java.util.Objects;

/**
 * 道路セグメント（エッジ）を表すクラスです。
 */
public class RouteEdge {
    private final RouteNode node1;
    private final RouteNode node2;
    private List<Location> calculatedPath;
    private EdgeMode edgeMode;
    private Location anchorLocation; // アンカーの位置

    public RouteEdge(RouteNode node1, RouteNode node2) {
        this(node1, node2, EdgeMode.STRAIGHT, null); // デフォルトで直線モード、アンカーなし
    }

    public RouteEdge(RouteNode node1, RouteNode node2, EdgeMode edgeMode) {
        this(node1, node2, edgeMode, null); // アンカーなしで初期化
    }

    public RouteEdge(RouteNode node1, RouteNode node2, EdgeMode edgeMode, Location anchorLocation) {
        this.node1 = node1;
        this.node2 = node2;
        this.edgeMode = edgeMode;
        this.anchorLocation = anchorLocation;
    }

    public RouteNode getNode1() {
        return node1;
    }

    public RouteNode getNode2() {
        return node2;
    }

    public List<Location> getCalculatedPath() {
        return calculatedPath;
    }

    public void setCalculatedPath(List<Location> calculatedPath) {
        this.calculatedPath = calculatedPath;
    }

    public EdgeMode getEdgeMode() {
        return edgeMode;
    }

    public void setEdgeMode(EdgeMode edgeMode) {
        this.edgeMode = edgeMode;
    }

    public Location getAnchorLocation() {
        return anchorLocation;
    }

    public void setAnchorLocation(Location anchorLocation) {
        this.anchorLocation = anchorLocation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteEdge routeEdge = (RouteEdge) o;
        // エッジはノードの順序を考慮しない（双方向）
        return (Objects.equals(node1, routeEdge.node1) && Objects.equals(node2, routeEdge.node2)) ||
               (Objects.equals(node1, routeEdge.node2) && Objects.equals(node2, routeEdge.node1));
    }

    @Override
    public int hashCode() {
        // ハッシュコードもノードの順序を考慮しない
        return Objects.hash(node1) + Objects.hash(node2);
    }
}
