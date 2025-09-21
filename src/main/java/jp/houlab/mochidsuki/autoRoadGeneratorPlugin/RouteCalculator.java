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
        CurveAnchor anchor = edge.getCurveAnchor(); // アンカーを取得

        // 始点と終点は必ずパスに含める
        path.add(p1.clone());

        // 接線ベクトルを計算
        // P1における接線は、P_prev -> P1 の方向
        Vector tangent1 = getTangentVectorForNode(node1, session, edge, p1Override, true);
        // P2における接線は、P2 -> P_next の方向
        Vector tangent2 = getTangentVectorForNode(node2, session, edge, p2Override, false);

        switch (edgeMode) {
            case STRAIGHT:
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                break;
            case ARC:
                calculateArc(p1, p2, tangent1, tangent2, anchor, step, path);
                break;
            case CLOTHOID:
                calculateClothoidApproximation(p1, p2, tangent1, tangent2, anchor, step, path);
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
     * ノードに接続する他のエッジの方向を考慮します。
     *
     * @param node 計算対象のノード
     * @param session 現在のルートセッション
     * @param currentEdge 現在計算中のエッジ
     * @param overrideLocation ノードのLocationを一時的に上書きする場合のLocation
     * @param isStartNodeOfCurrentEdge nodeがcurrentEdgeの始点であるか (true) 終点であるか (false)
     * @return 計算された接線ベクトル
     */
    private Vector getTangentVectorForNode(RouteNode node, RouteSession session, RouteEdge currentEdge, @Nullable Location overrideLocation, boolean isStartNodeOfCurrentEdge) {
        Location actualNodeLocation = (overrideLocation != null) ? overrideLocation : node.getLocation();
        List<RouteEdge> connectedEdges = session.getEdgesConnectedToNode(node);
        List<RouteEdge> otherEdges = new ArrayList<>(connectedEdges);
        otherEdges.remove(currentEdge); // 現在計算中のエッジ自体は除外

        if (otherEdges.isEmpty()) { // ノードがパスの端点（currentEdgeしか接続されていない）
            if (isStartNodeOfCurrentEdge) {
                // P1がパスの始点 -> P1からP2への方向
                return currentEdge.getNode2().getLocation().toVector().subtract(actualNodeLocation.toVector()).normalize();
            } else {
                // P2がパスの終点 -> P1からP2への方向
                return actualNodeLocation.toVector().subtract(currentEdge.getNode1().getLocation().toVector()).normalize();
            }
        } else if (otherEdges.size() == 1) { // ノードが単純な中間点（currentEdgeともう1つのエッジに接続）
            RouteEdge otherEdge = otherEdges.get(0);
            RouteNode neighborNode = otherEdge.getOtherNode(node);
            if (isStartNodeOfCurrentEdge) {
                // P1の接線 -> NeighborからP1への方向
                return actualNodeLocation.toVector().subtract(neighborNode.getLocation().toVector()).normalize();
            } else {
                // P2の接線 -> P2からNeighborへの方向
                return neighborNode.getLocation().toVector().subtract(actualNodeLocation.toVector()).normalize();
            }
        } else { // ノードが分岐点（3つ以上のエッジに接続）
            // 分岐点では「180°」の接線は定義しにくい。ここでは、接続する全てのエッジの方向を平均するヒューリスティックを使用。
            // ただし、currentEdgeの方向も考慮に入れることで、より自然な接続を目指す。
            Vector tangentSum = new Vector(0, 0, 0);
            for (RouteEdge ce : connectedEdges) {
                Location otherNodeLoc = ce.getOtherNode(node).getLocation();
                if (node.equals(ce.getNode1())) { // ノードがceの始点の場合
                    tangentSum.add(otherNodeLoc.toVector().subtract(actualNodeLocation.toVector()));
                } else { // ノードがceの終点の場合
                    tangentSum.add(actualNodeLocation.toVector().subtract(otherNodeLoc.toVector()));
                }
            }
            return tangentSum.normalize();
        }
    }

    /**
     * 2つのノード間に円弧を計算します。
     * 始点、終点、およびアンカーの3点を通る3D円弧を生成します。
     * この実装では、隣接するエッジとの微分可能性を保証します。
     */
    private void calculateArc(Location p1, Location p2, Vector tangent1, Vector tangent2, @Nullable CurveAnchor anchor, double step, List<Location> path) {
        Location anchorLoc = (anchor != null) ? anchor.getLocation() : null;

        // 3点が与えられた場合、それらを通る円を計算
        if (anchorLoc != null) {
            // 3点が一直線上にあるかチェック
            if (isCollinear(p1, p2, anchorLoc)) {
                // 直線として補間
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                return;
            }

            // 3点を通る円の中心と半径を計算 (3D)
            // この計算は複雑なので、ここでは簡略化された2D円弧のY軸補間を維持しつつ、
            // XZ平面での円弧を3点を通るように調整する。
            // 厳密な3D円は、3点から平面を決定し、その平面上で円を求める必要がある。
            // ここでは、XZ平面での円弧とY軸の二次ベジェ補間を組み合わせる。

            // XZ平面での円の中心と半径を求める
            Vector p1_xz = new Vector(p1.getX(), 0, p1.getZ());
            Vector p2_xz = new Vector(p2.getX(), 0, p2.getZ());
            Vector anchor_xz = new Vector(anchorLoc.getX(), 0, anchorLoc.getZ());

            // 3点を通る円の中心と半径を求める関数 (XZ平面)
            CircleData circleData = getCircleFromThreePoints(p1_xz, p2_xz, anchor_xz);

            if (circleData == null || circleData.radius > 1000 || circleData.radius < 0.1) { // 円が計算できない、または異常な場合
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                return;
            }

            Vector center_xz = circleData.center;
            double radius = circleData.radius;

            // 開始角度と終了角度を計算
            double startAngle = Math.atan2(p1_xz.getZ() - center_xz.getZ(), p1_xz.getX() - center_xz.getX());
            double endAngle = Math.atan2(p2_xz.getZ() - center_xz.getZ(), p2_xz.getX() - center_xz.getX());
            double anchorAngle = Math.atan2(anchor_xz.getZ() - center_xz.getZ(), anchor_xz.getX() - center_xz.getX());

            // 角度の差を計算し、アンカーを通るように調整
            double angleDiff = endAngle - startAngle;
            // アンカーが円弧の正しい側にあるか確認し、角度の差を調整
            if (!isAngleBetween(anchorAngle, startAngle, endAngle, angleDiff)) {
                angleDiff += (angleDiff > 0) ? -2 * Math.PI : 2 * Math.PI;
            }

            for (double t = step; t < 1.0; t += step) {
                double currentAngle = startAngle + angleDiff * t;
                double x = center_xz.getX() + radius * Math.cos(currentAngle);
                double z = center_xz.getZ() + radius * Math.sin(currentAngle);
                
                // Y座標はP1, Anchor, P2を通る二次ベジェ曲線で補間
                double y = (1 - t) * (1 - t) * p1.getY() + 2 * (1 - t) * t * anchorLoc.getY() + t * t * p2.getY();
                path.add(new Location(p1.getWorld(), x, y, z));
            }

        } else { // アンカーがない場合、接線ベースの円弧計算 (旧ロジックを簡略化)
            Vector p1Vec = p1.toVector();
            Vector p2Vec = p2.toVector();

            Vector tan1_xz = new Vector(tangent1.getX(), 0, tangent1.getZ()).normalize();
            Vector tan2_xz = new Vector(tangent2.getX(), 0, tangent2.getZ()).normalize();

            Vector p1_xz = new Vector(p1.getX(), 0, p1.getZ());
            Vector p2_xz = new Vector(p2.getX(), 0, p2.getZ());

            Vector normal1_xz = new Vector(-tan1_xz.getZ(), 0, tan1_xz.getX());
            Vector normal2_xz = new Vector(-tan2_xz.getZ(), 0, tan2_xz.getX());

            double det = normal1_xz.getX() * normal2_xz.getZ() - normal1_xz.getZ() * normal2_xz.getX();

            if (Math.abs(det) < 1e-6) { // 法線が平行 (接線も平行) -> 直線
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

            double startAngle = Math.atan2(p1_xz.getZ() - center_xz.getZ(), p1_xz.getX() - center_xz.getX());
            double endAngle = Math.atan2(p2_xz.getZ() - center_xz.getZ(), p2_xz.getX() - center_xz.getX());

            Vector p1ToCenter_xz = center_xz.clone().subtract(p1_xz);
            Vector p1ArcDirection_xz = new Vector(-p1ToCenter_xz.getZ(), 0, p1ToCenter_xz.getX()).normalize();

            double angleDiff = endAngle - startAngle;

            if (tan1_xz.dot(p1ArcDirection_xz) < 0) { 
                if (angleDiff > 0) angleDiff -= 2 * Math.PI; 
                else if (angleDiff < 0) angleDiff += 2 * Math.PI; 
            }

            for (double t = step; t < 1.0; t += step) {
                double currentAngle = startAngle + angleDiff * t;
                double x = center_xz.getX() + radius * Math.cos(currentAngle);
                double z = center_xz.getZ() + radius * Math.sin(currentAngle);
                double y = lerp(p1, p2, t).getY(); // Y座標は線形補間
                path.add(new Location(p1.getWorld(), x, y, z));
            }
        }
    }

    /**
     * 2つのノード間にクロソイド曲線の近似を計算します。
     * 接線ベクトルを考慮してベジェ曲線を生成します。
     * アンカーが存在する場合、そのY座標を曲線の高さに反映させます。
     */
    private void calculateClothoidApproximation(Location p1, Location p2, Vector tangent1, Vector tangent2, @Nullable CurveAnchor anchor, double step, List<Location> path) {
        Vector p1Vec = p1.toVector();
        Vector p2Vec = p2.toVector();

        double chordLength = p2Vec.clone().subtract(p1Vec).length();

        if (chordLength < 0.1) { // ノードが近すぎる場合、直線として扱う
            for (double t = step; t < 1.0; t += step) {
                path.add(lerp(p1, p2, t));
            }
            return;
        }

        // Define virtual points for Catmull-Rom spline in XZ plane
        // These points help define the tangents at p1 and p2
        Location p_prev_xz = p1.clone().subtract(new Vector(tangent1.getX(), 0, tangent1.getZ()).normalize().multiply(TANGENT_LENGTH_FACTOR * chordLength));
        Location p_next_xz = p2.clone().add(new Vector(tangent2.getX(), 0, tangent2.getZ()).normalize().multiply(TANGENT_LENGTH_FACTOR * chordLength));

        if (anchor != null) {
            // Use two Catmull-Rom segments for XZ: p1-anchor and anchor-p2
            // Segment 1: p1 to anchor
            // Control points: p_prev_xz, p1_xz, anchor_xz, p2_xz
            Location anchorLoc = anchor.getLocation();
            Location p1_xz = new Location(p1.getWorld(), p1.getX(), 0, p1.getZ());
            Location p2_xz = new Location(p2.getWorld(), p2.getX(), 0, p2.getZ());
            Location anchor_xz = new Location(anchorLoc.getWorld(), anchorLoc.getX(), 0, anchorLoc.getZ());

            double segment1Length = p1_xz.distance(anchor_xz);
            double segment2Length = anchor_xz.distance(p2_xz);
            double totalLength = segment1Length + segment2Length;

            for (double t = step; t < 1.0; t += step) {
                Location currentPoint;
                double currentY;
                if (t * chordLength < segment1Length) { // First segment
                    double t_segment = (t * chordLength) / segment1Length; // Normalize t for segment
                    currentPoint = getPointOnCatmullRomSpline(t_segment, p_prev_xz, p1_xz, anchor_xz, p2_xz);
                } else { // Second segment
                    double t_segment = (t * chordLength - segment1Length) / segment2Length; // Normalize t for segment
                    currentPoint = getPointOnCatmullRomSpline(t_segment, p1_xz, anchor_xz, p2_xz, p_next_xz);
                }

                // Y座標はP1, Anchor, P2を通る二次ベジェ曲線で補間
                currentY = (1 - t) * (1 - t) * p1.getY() + 2 * (1 - t) * t * anchorLoc.getY() + t * t * p2.getY();
                path.add(new Location(p1.getWorld(), currentPoint.getX(), currentY, currentPoint.getZ()));
            }

        } else {
            // Fallback to a single Catmull-Rom segment if no anchor
            // Control points: p_prev_xz, p1_xz, p2_xz, p_next_xz
            Location p1_xz = new Location(p1.getWorld(), p1.getX(), 0, p1.getZ());
            Location p2_xz = new Location(p2.getWorld(), p2.getX(), 0, p2.getZ());

            for (double t = step; t < 1.0; t += step) {
                Location currentPoint = getPointOnCatmullRomSpline(t, p_prev_xz, p1_xz, p2_xz, p_next_xz);
                double y = lerp(p1, p2, t).getY(); // Y座標は線形補間
                path.add(new Location(p1.getWorld(), currentPoint.getX(), y, currentPoint.getZ()));
            }
        }
    }

    // ヘルパークラスとメソッド
    private static class CircleData {
        public Vector center;
        public double radius;

        public CircleData(Vector center, double radius) {
            this.center = center;
            this.radius = radius;
        }
    }

    /**
     * 3つの点を通る円の中心と半径を計算します (XZ平面)。
     * @param p1 点1 (XZ)
     * @param p2 点2 (XZ)
     * @param p3 点3 (XZ)
     * @return 円の中心と半径を含むCircleDataオブジェクト、または3点が一直線上にある場合はnull
     */
    private CircleData getCircleFromThreePoints(Vector p1, Vector p2, Vector p3) {
        double p1x = p1.getX(), p1z = p1.getZ();
        double p2x = p2.getX(), p2z = p2.getZ();
        double p3x = p3.getX(), p3z = p3.getZ();

        double D = 2 * (p1x * (p2z - p3z) + p2x * (p3z - p1z) + p3x * (p1z - p2z));

        if (Math.abs(D) < 1e-6) { // 3点が一直線上にある
            return null;
        }

        double center_x = ((p1x * p1x + p1z * p1z) * (p2z - p3z) +
                           (p2x * p2x + p2z * p2z) * (p3z - p1z) +
                           (p3x * p3x + p3z * p3z) * (p1z - p2z)) / D;

        double center_z = ((p1x * p1x + p1z * p1z) * (p3x - p2x) +
                           (p2x * p2x + p2z * p2z) * (p1x - p3x) +
                           (p3x * p3x + p3z * p3z) * (p2x - p1x)) / D;

        Vector center = new Vector(center_x, 0, center_z);
        double radius = center.distance(p1);

        return new CircleData(center, radius);
    }

    /**
     * 3つの点が一直線上にあるか判定します。
     */
    private boolean isCollinear(Location p1, Location p2, Location p3) {
        // XZ平面で判定
        Vector v1 = p2.toVector().subtract(p1.toVector());
        Vector v2 = p3.toVector().subtract(p1.toVector());
        // 外積のY成分がほぼ0であれば共線
        return Math.abs(v1.getX() * v2.getZ() - v1.getZ() * v2.getX()) < 1e-6;
    }

    /**
     * 角度が指定された開始角度と終了角度の間にあるか判定します。
     * 角度のラップアラウンドを考慮します。
     */
    private boolean isAngleBetween(double angle, double startAngle, double endAngle, double angleDiff) {
        // 角度を0から2PIの範囲に正規化
        angle = (angle % (2 * Math.PI) + (2 * Math.PI)) % (2 * Math.PI);
        startAngle = (startAngle % (2 * Math.PI) + (2 * Math.PI)) % (2 * Math.PI);
        endAngle = (endAngle % (2 * Math.PI) + (2 * Math.PI)) % (2 * Math.PI);

        if (angleDiff < 0) { // 時計回り
            return (angle <= startAngle && angle >= endAngle) || (angle <= startAngle && endAngle > startAngle) || (angle >= endAngle && endAngle > startAngle);
        } else { // 反時計回り
            return (angle >= startAngle && angle <= endAngle) || (angle >= startAngle && endAngle < startAngle) || (angle <= endAngle && endAngle < startAngle);
        }
    }

    /**
     * Catmull-Romスプラインの公式に基づいて、特定の位置(t)の座標を計算します。
     * Y軸は直線補間を使用します。
     */
    private Location getPointOnCatmullRomSpline(double t, Location p0, Location p1, Location p2, Location p3) {
        // Catmull-Rom spline formula
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5 * ((2 * p1.getX()) + (-p0.getX() + p2.getX()) * t + (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2 + (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);
        double y = 0.5 * ((2 * p1.getY()) + (-p0.getY() + p2.getY()) * t + (2 * p0.getY() - 5 * p1.getY() + 4 * p2.getY() - p3.getY()) * t2 + (-p0.getY() + 3 * p1.getY() - 3 * p2.getY() + p3.getY()) * t3);
        double z = 0.5 * ((2 * p1.getZ()) + (-p0.getZ() + p2.getZ()) * t + (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2 + (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);

        return new Location(p1.getWorld(), x, y, z);
    }
}
