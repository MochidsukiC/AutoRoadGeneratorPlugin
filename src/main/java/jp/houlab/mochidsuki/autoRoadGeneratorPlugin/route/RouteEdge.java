package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route;

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
    private CurveAnchor curveAnchor; // アンカーポイントを追加

    public RouteEdge(RouteNode node1, RouteNode node2) {
        this(node1, node2, EdgeMode.STRAIGHT, null); // デフォルトで直線モード、アンカーなし
    }

    public RouteEdge(RouteNode node1, RouteNode node2, EdgeMode edgeMode) {
        this(node1, node2, edgeMode, null); // アンカーなし
    }

    public RouteEdge(RouteNode node1, RouteNode node2, EdgeMode edgeMode, CurveAnchor curveAnchor) {
        this.node1 = node1;
        this.node2 = node2;
        this.edgeMode = edgeMode;
        this.curveAnchor = curveAnchor;
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

    public CurveAnchor getCurveAnchor() {
        return curveAnchor;
    }

    public void setCurveAnchor(CurveAnchor curveAnchor) {
        this.curveAnchor = curveAnchor;
    }

    /**
     * このエッジの、指定されたノードではないもう一方のノードを返します。
     * @param node このエッジのいずれかのノード
     * @return 指定されたノードではないもう一方のノード
     * @throws IllegalArgumentException 指定されたノードがこのエッジの一部ではない場合
     */
    public RouteNode getOtherNode(RouteNode node) {
        if (node1.equals(node)) {
            return node2;
        } else if (node2.equals(node)) {
            return node1;
        } else {
            throw new IllegalArgumentException("Node is not part of this edge.");
        }
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
