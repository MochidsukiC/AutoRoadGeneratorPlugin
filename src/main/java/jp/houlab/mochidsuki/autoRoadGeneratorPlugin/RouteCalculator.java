package jp.houlab.mochidsuki.autoRoadGeneratorPlugin;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 道路のパスを計算するクラスです。
 */
public class RouteCalculator {

    // private static final double DEFAULT_ARC_SAGITTA_FACTOR = 0.25; // 弦の長さに対する円弧の高さの比率 (不要になった)
    private static final double DEFAULT_CLOTHOID_BEND_FACTOR = 0.5; // クロソイドの曲がり具合の係数
    private static final double TANGENT_LENGTH_FACTOR = 0.5; // 接線ベクトルの長さを決定する係数

    /**
     * 2つのノード間のパスを計算します。
     *
     * @param edge   計算対象のエッジ
     * @param session 現在のルートセッション
     * @param step パス上の点を生成する密度
     * @param p1Override node1のLocationを一時的に上書きする場合のLocation
     * @param p2Override node2のLocationを一時的に上書きする場合のLocation
     * @return 生成された高密度のLocationリスト
     */
    public List<Location> calculate(RouteEdge edge, RouteSession session, double step,
                                    @Nullable Location p1Override, @Nullable Location p2Override) {
        List<Location> path = new ArrayList<>();

        RouteNode node1 = edge.getNode1();
        RouteNode node2 = edge.getNode2();
        Location p1 = (p1Override != null) ? p1Override : node1.getLocation();
        Location p2 = (p2Override != null) ? p2Override : node2.getLocation();
        EdgeMode edgeMode = edge.getEdgeMode();
        Location anchorLocation = edge.getAnchorLocation(); // アンカー位置を取得

        // 始点と終点は必ずパスに含める
        path.add(p1.clone());

        // 接線ベクトルを計算
        // ノードにおける接線は、そのノードに接続する全てのエッジの方向を平均して決定する
        Vector tangent1 = getTangentVectorForNode(node1, session, p1Override);
        Vector tangent2 = getTangentVectorForNode(node2, session, p2Override);

        switch (edgeMode) {
            case STRAIGHT:
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                break;
            case ARC:
                calculateArc(p1, p2, tangent1, tangent2, anchorLocation, step, path);
                break;
            case CLOTHOID:
                calculateClothoidApproximation(p1, p2, tangent1, tangent2, anchorLocation, step, path);
                break;
            default:
                // 未定義のモードの場合も線形補間
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                break;
        }
        // 終点も必ずパスに含める（重複を避けるため、最後のステップで追加）
        if (!path.get(path.size() - 1).equals(p2)) {
            path.add(p2.clone());
        }
        return path;
    }

    /**
     * 2つのLocation間で線形補間を行います。
     */
    private Location lerp(Location loc1, Location loc2, double t) {
        double x = loc1.getX() * (1 - t) + loc2.getX() * t;
        double y = loc1.getY() * (1 - t) + loc2.getY() * t;
        double z = loc1.getZ() * (1 - t) + loc2.getZ() * t;
        return new Location(loc1.getWorld(), x, y, z);
    }

    /**
     * 指定されたノードにおける接線ベクトルを計算します。
     * ノードに接続する全てのエッジの方向を平均して決定します。
     *
     * @param node 計算対象のノード
     * @param session 現在のルートセッション
     * @param overrideLocation ノードのLocationを一時的に上書きする場合のLocation
     * @return 計算された接線ベクトル
     */
    private Vector getTangentVectorForNode(RouteNode node, RouteSession session, @Nullable Location overrideLocation) {
        Location actualNodeLocation = (overrideLocation != null) ? overrideLocation : node.getLocation();
        List<RouteEdge> connectedEdges = session.getEdgesConnectedToNode(node);
        Vector tangentSum = new Vector(0, 0, 0);
        int count = 0;

        for (RouteEdge connectedEdge : connectedEdges) {
            Location otherNodeLocation;
            if (connectedEdge.getNode1().equals(node)) {
                otherNodeLocation = connectedEdge.getNode2().getLocation();
            } else { // connectedEdge.getNode2().equals(node)
                otherNodeLocation = connectedEdge.getNode1().getLocation();
            }
            // ノードから隣接ノードへの方向ベクトルを加算
            tangentSum.add(otherNodeLocation.toVector().subtract(actualNodeLocation.toVector()));
            count++;
        }

        if (count > 0) {
            return tangentSum.normalize();
        } else {
            // 接続されたエッジがない場合（孤立ノード）、デフォルトの接線（例：Z軸方向）を返す
            // このケースは通常、エッジの計算時には発生しないはずだが、念のため
            return new Vector(0, 0, 1); // デフォルトとしてZ軸方向を返す
        }
    }

    /**
     * 2つのノード間に円弧を計算します。
     * 接線ベクトルを考慮して円弧を生成します。
     * この実装では、隣接するエッジとの微分可能性を保証します。
     */
    private void calculateArc(Location p1, Location p2, Vector tangent1, Vector tangent2,
                              @Nullable Location anchorLocation, double step, List<Location> path) {
        Vector p1Vec = p1.toVector();
        Vector p2Vec = p2.toVector();

        // XZ平面での計算に限定
        Vector p1_xz = new Vector(p1.getX(), 0, p1.getZ());
        Vector p2_xz = new Vector(p2.getX(), 0, p2.getZ());
        Vector tan1_xz = new Vector(tangent1.getX(), 0, tangent1.getZ()).normalize();
        Vector tan2_xz = new Vector(tangent2.getX(), 0, tangent2.getZ()).normalize();

        Vector chord_xz = p2_xz.clone().subtract(p1_xz);
        double chordLength_xz = chord_xz.length();

        if (chordLength_xz < 0.1) { // ノードが近すぎる場合、直線として扱う
            for (double t = step; t < 1.0; t += step) {
                path.add(lerp(p1, p2, t));
            }
            return;
        }

        // p1における接線に垂直なベクトル (法線ベクトル)
        Vector normal1_xz = new Vector(-tan1_xz.getZ(), 0, tan1_xz.getX());
        // p2における接線に垂直なベクトル (法線ベクトル)
        Vector normal2_xz = new Vector(-tan2_xz.getZ(), 0, tan2_xz.getX());

        // 円の中心を求める (XZ平面)
        // p1を通るnormal1_xzの直線: p1_xz + s * normal1_xz
        // p2を通るnormal2_xzの直線: p2_xz + t * normal2_xz
        // 交点 (center_xz) を求める
        // p1_xz.x + s * normal1_xz.x = p2_xz.x + t * normal2_xz.x
        // p1_xz.z + s * normal1_xz.z = p2_xz.z + t * normal2_xz.z

        double det = normal1_xz.getX() * normal2_xz.getZ() - normal1_xz.getZ() * normal2_xz.getX();

        if (Math.abs(det) < 1e-6) { // 法線が平行 (接線も平行) -> 直線または非常に大きな円弧
            for (double t = step; t < 1.0; t += step) {
                path.add(lerp(p1, p2, t));
            }
            return;
        }

        double s = ( (p2_xz.getX() - p1_xz.getX()) * normal2_xz.getZ() - (p2_xz.getZ() - p1_xz.getZ()) * normal2_xz.getX() ) / det;

        Vector center_xz = p1_xz.clone().add(normal1_xz.multiply(s));
        double radius = center_xz.distance(p1_xz);

        if (radius > 1000 || radius < 0.1) { // 半径が異常に大きい、または小さい場合も直線として扱う
            for (double t = step; t < 1.0; t += step) {
                path.add(lerp(p1, p2, t));
            }
            return;
        }

        // 開始角度と終了角度を計算
        double startAngle = Math.atan2(p1_xz.getZ() - center_xz.getZ(), p1_xz.getX() - center_xz.getX());
        double endAngle = Math.atan2(p2_xz.getZ() - center_xz.getZ(), p2_xz.getX() - center_xz.getX());

        // 円弧がどちらの方向に回転するかを接線から判断し、角度の差を調整
        // p1での接線方向と、p1から円の中心へのベクトルに垂直な方向（円弧の進行方向）が一致するようにする
        Vector p1ToCenter_xz = center_xz.clone().subtract(p1_xz);
        Vector p1ArcDirection_xz = new Vector(-p1ToCenter_xz.getZ(), 0, p1ToCenter_xz.getX()).normalize();

        double angleDiff = endAngle - startAngle;

        // 接線方向と円弧の進行方向が逆の場合、角度の差を調整して正しい回転方向にする
        if (tan1_xz.dot(p1ArcDirection_xz) < 0) { // 接線と円弧の進行方向が逆
            if (angleDiff > 0) angleDiff -= 2 * Math.PI; // 反時計回りの場合、時計回りに調整
            else if (angleDiff < 0) angleDiff += 2 * Math.PI; // 時計回りの場合、反時計回りに調整
        }

        // Y座標は二次ベジェ曲線で補間
        for (double t = step; t < 1.0; t += step) {
            double currentAngle = startAngle + angleDiff * t;
            double x = center_xz.getX() + radius * Math.cos(currentAngle);
            double z = center_xz.getZ() + radius * Math.sin(currentAngle);
            
            double y;
            if (anchorLocation != null) {
                // 二次ベジェ曲線: B(t) = (1-t)^2*P0 + 2(1-t)t*P1 + t^2*P2
                y = (1 - t) * (1 - t) * p1.getY() +
                    2 * (1 - t) * t * anchorLocation.getY() +
                    t * t * p2.getY();
            } else {
                y = lerp(p1, p2, t).getY(); // アンカーがない場合は線形補間
            }
            path.add(new Location(p1.getWorld(), x, y, z));
        }
    }

    /**
     * 2つのノード間にクロソイド曲線の近似を計算します。
     * 接線ベクトルを考慮してベジェ曲線を生成します。
     */
    private void calculateClothoidApproximation(Location p1, Location p2, Vector tangent1, Vector tangent2,
                                                @Nullable Location anchorLocation, double step, List<Location> path) {
        // Cubic Bezier curve using p1, p2 and tangent vectors as control points

        // This is a simplified approximation and not a true Clothoid.
        // A true Clothoid requires more complex math involving Fresnel integrals or numerical integration.

        Vector p1Vec = p1.toVector();
        Vector p2Vec = p2.toVector();

        Vector chord = p2Vec.clone().subtract(p1Vec);
        double chordLength = chord.length();

        if (chordLength < 0.1) { // ノードが近すぎる場合、直線として扱う
            for (double t = step; t < 1.0; t += step) {
                path.add(lerp(p1, p2, t));
            }
            return;
        }

        // 制御点1: p1からtangent1の方向に伸ばす
        Vector control1Vec = p1Vec.clone().add(tangent1.clone().multiply(chordLength * TANGENT_LENGTH_FACTOR));
        Location control1 = new Location(p1.getWorld(), control1Vec.getX(), control1Vec.getY(), control1Vec.getZ());

        // 制御点2: p2からtangent2の逆方向に伸ばす
        Vector control2Vec = p2Vec.clone().subtract(tangent2.clone().multiply(chordLength * TANGENT_LENGTH_FACTOR));
        Location control2 = new Location(p2.getWorld(), control2Vec.getX(), control2Vec.getY(), control2Vec.getZ());

        // Y座標は二次ベジェ曲線で補間
        for (double t = step; t < 1.0; t += step) {
            // Cubic Bezier formula: B(t) = (1-t)^3*P0 + 3(1-t)^2*t*P1 + 3(1-t)*t^2*P2 + t^3*P3
            double oneMinusT = (1 - t);
            double oneMinusT2 = oneMinusT * oneMinusT;
            double t2 = t * t;

            double x = oneMinusT2 * oneMinusT * p1.getX() +
                       3 * oneMinusT2 * t * control1.getX() +
                       3 * oneMinusT * t2 * control2.getX() +
                       t2 * t * p2.getX();

            double z = oneMinusT2 * oneMinusT * p1.getZ() +
                       3 * oneMinusT2 * t * control1.getZ() +
                       3 * oneMinusT * t2 * control2.getZ() +
                       t2 * t * p2.getZ();

            double y;
            if (anchorLocation != null) {
                // 二次ベジェ曲線: B(t) = (1-t)^2*P0 + 2(1-t)t*P1 + t^2*P2
                y = (1 - t) * (1 - t) * p1.getY() +
                    2 * (1 - t) * t * anchorLocation.getY() +
                    t * t * p2.getY();
            } else {
                y = lerp(p1, p2, t).getY(); // アンカーがない場合は線形補間
            }
            path.add(new Location(p1.getWorld(), x, y, z));
        }
    }
}
