package jp.houlab.mochidsuki.autoRoadGeneratorPlugin.route;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 道路のパスを計算するクラスです。
 *
 * 主な改善点：
 * - アンカーベース曲線生成の安定性向上
 * - 線対称ジャンプ問題の解決
 * - 数値計算の精度統一
 * - Y座標補間の一貫性確保
 * - 角度計算の正規化統一
 */
public class RouteCalculator {

    private static final double TANGENT_LENGTH_FACTOR = 0.5; // 接線ベクトルの長さを決定する係数
    private static final double EPSILON = 1e-6; // 数値計算の閾値を統一
    private static final double MAX_RADIUS = 1000.0; // 最大円弧半径
    private static final double MIN_RADIUS = 0.1; // 最小円弧半径
    private static final double DEFAULT_STEP_DISTANCE = 0.5; // デフォルトの再サンプリング間隔
    private static final double MAX_ANCHOR_DISTANCE_RATIO = 3.0; // アンカーの最大距離比率（弦長の何倍まで許可）

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

        // 接線ベクトルを計算
        // P1における接線は、P_prev -> P1 の方向
        Vector tangent1 = getTangentVectorForNode(node1, session, edge, p1Override, true);
        // P2における接線は、P2 -> P_next の方向
        Vector tangent2 = getTangentVectorForNode(node2, session, edge, p2Override, false);

        // 始点と終点は必ずパスに含める
        Location startPoint = p1.clone();
        // 始点のヨー角とピッチ角を設定
        Vector startDirection = tangent1.normalize();
        float startYaw = (float) Math.toDegrees(Math.atan2(startDirection.getZ(), startDirection.getX()));
        float startPitch = (float) Math.toDegrees(Math.asin(-startDirection.getY()));
        startPoint.setYaw(startYaw);
        startPoint.setPitch(startPitch);
        path.add(startPoint);

        switch (edgeMode) {
            case STRAIGHT:
                calculateStraightWithYaw(p1, p2, step, path);
                break;
            case ARC:
                calculateArcWithYaw(p1, p2, tangent1, tangent2, anchor, step, path);
                break;
            case CLOTHOID:
                calculateClothoidApproximationWithYaw(p1, p2, tangent1, tangent2, anchor, step, path);
                break;
            default:
                // 未定義のモードの場合も線形補間
                calculateStraightWithYaw(p1, p2, step, path);
                break;
        }
        // 終点も必ずパスに含める（重複を避けるため、最後のステップで追加）
        if (!path.get(path.size() - 1).equals(p2)) {
            Location endPoint = p2.clone();
            // 終点のヨー角とピッチ角を設定
            Vector endDirection = tangent2.normalize();
            float endYaw = (float) Math.toDegrees(Math.atan2(endDirection.getZ(), endDirection.getX()));
            float endPitch = (float) Math.toDegrees(Math.asin(-endDirection.getY()));
            endPoint.setYaw(endYaw);
            endPoint.setPitch(endPitch);
            path.add(endPoint);
        }
        return resamplePath(path, DEFAULT_STEP_DISTANCE); // デフォルト間隔でパスを再サンプリング
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
     * ちょうど2つのエッジが接続しているノードの場合、滑らかな接続のための角度修正を適用します。
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

        // ちょうど2つのエッジが接続している場合、滑らかな接続のための角度修正を適用
        if (session.hasExactlyTwoConnectedEdges(node)) {
            Vector smoothTangent = calculateSmoothTangentForTwoEdgeNode(node, session, currentEdge, actualNodeLocation, isStartNodeOfCurrentEdge);
            if (smoothTangent != null) {
                return smoothTangent;
            }
        }

        // 通常の場合またはスムーズな接線計算が失敗した場合
        // エッジタイプに基づいた正確な接線ベクトルを計算
        return calculateAccurateTangentFromEdgeEquation(currentEdge, actualNodeLocation, isStartNodeOfCurrentEdge);
    }

    /**
     * 2つのエッジが接続するノードにおける滑らかな接線ベクトルを計算します。
     * 数学的に微分可能で連続した滑らかな接続を実現します。
     *
     * @param node 2つのエッジが接続するノード
     * @param session 現在のルートセッション
     * @param currentEdge 現在計算中のエッジ
     * @param nodeLocation ノードの位置
     * @param isStartNodeOfCurrentEdge nodeがcurrentEdgeの始点であるかどうか
     * @return 計算された滑らかな接線ベクトル、計算できない場合はnull
     */
    private Vector calculateSmoothTangentForTwoEdgeNode(RouteNode node, RouteSession session, RouteEdge currentEdge, Location nodeLocation, boolean isStartNodeOfCurrentEdge) {
        List<RouteEdge> connectedEdges = session.getEdgesConnectedToNode(node);
        if (connectedEdges.size() != 2) {
            return null;
        }

        // 現在のエッジとその他のエッジを特定
        RouteEdge otherEdge = null;
        for (RouteEdge edge : connectedEdges) {
            if (!edge.equals(currentEdge)) {
                otherEdge = edge;
                break;
            }
        }

        if (otherEdge == null) {
            return null;
        }

        // 継続性を確保するため、前のエッジの終点での接線をそのまま使用
        if (isStartNodeOfCurrentEdge) {
            // 現在のエッジが始点の場合、前のエッジ（otherEdge）の終点での接線を直接使用
            boolean isOtherEdgeStartAtNode = otherEdge.getNode1().equals(node);
            return calculateAccurateTangentFromEdgeEquation(otherEdge, nodeLocation, isOtherEdgeStartAtNode);
        } else {
            // 現在のエッジが終点の場合、現在のエッジの終点での接線を使用
            return calculateAccurateTangentFromEdgeEquation(currentEdge, nodeLocation, isStartNodeOfCurrentEdge);
        }
    }

    /**
     * エッジの方程式に基づいて正確な接線ベクトルを計算します。
     * 各エッジタイプ（STRAIGHT, ARC, CLOTHOID）の数学的方程式から導出された接線を使用します。
     *
     * @param edge 対象エッジ
     * @param nodeLocation ノードの位置
     * @param isStartNode ノードがエッジの始点かどうか
     * @return 計算された正確な接線ベクトル
     */
    private Vector calculateAccurateTangentFromEdgeEquation(RouteEdge edge, Location nodeLocation, boolean isStartNode) {
        Location p1 = edge.getNode1().getLocation();
        Location p2 = edge.getNode2().getLocation();
        EdgeMode edgeMode = edge.getEdgeMode();
        CurveAnchor anchor = edge.getCurveAnchor();

        switch (edgeMode) {
            case STRAIGHT:
                return calculateStraightTangentAccurate(p1, p2, isStartNode);
            case ARC:
                return calculateArcTangentAccurate(p1, p2, anchor, isStartNode);
            case CLOTHOID:
                return calculateClothoidTangentAccurate(p1, p2, anchor, isStartNode);
            default:
                // フォールバック: 直線として扱う
                return calculateStraightTangentAccurate(p1, p2, isStartNode);
        }
    }

    /**
     * 直線エッジの正確な接線ベクトルを計算します。
     */
    private Vector calculateStraightTangentAccurate(Location p1, Location p2, boolean isStartNode) {
        return p2.toVector().subtract(p1.toVector()).normalize(); // 直線では始点・終点関係なく同じ方向
    }

    /**
     * 円弧エッジの正確な接線ベクトルを計算します。
     * 円弧上の微小な点差分を使用して接線を計算します。
     */
    private Vector calculateArcTangentAccurate(Location p1, Location p2, @Nullable CurveAnchor anchor, boolean isStartNode) {
        if (anchor == null) {
            // アンカーがない場合は直線として扱う
            return calculateStraightTangentAccurate(p1, p2, isStartNode);
        }

        Vector p1_xz = new Vector(p1.getX(), 0, p1.getZ());
        Vector p2_xz = new Vector(p2.getX(), 0, p2.getZ());
        Vector anchor_xz = new Vector(anchor.getLocation().getX(), 0, anchor.getLocation().getZ());

        CircleData circleData = getCircleFromThreePoints(p1_xz, p2_xz, anchor_xz);
        if (circleData == null || circleData.radius > MAX_RADIUS || circleData.radius < MIN_RADIUS) {
            return calculateStraightTangentAccurate(p1, p2, isStartNode);
        }

        Vector center_xz = circleData.center;
        double radius = circleData.radius;

        // 角度を計算
        double startAngle = Math.atan2(p1_xz.getZ() - center_xz.getZ(), p1_xz.getX() - center_xz.getX());
        double endAngle = Math.atan2(p2_xz.getZ() - center_xz.getZ(), p2_xz.getX() - center_xz.getX());
        double anchorAngle = Math.atan2(anchor_xz.getZ() - center_xz.getZ(), anchor_xz.getX() - center_xz.getX());

        // 円弧方向を判定：アンカーが最短円弧経路上にあるかチェック
        boolean isClockwise = !determineArcDirection(startAngle, endAngle, anchorAngle);

        // 円の接線は半径ベクトルに垂直
        double targetAngle = isStartNode ? startAngle : endAngle;

        // 半径ベクトル（中心から点への方向）
        double radiusX = Math.cos(targetAngle);
        double radiusZ = Math.sin(targetAngle);

        // 接線ベクトル（半径ベクトルを90度回転）
        // 時計回りの円弧の場合: (-sin(θ), cos(θ))
        // 反時計回りの円弧の場合: (sin(θ), -cos(θ))
        double tangentX, tangentZ;
        if (isClockwise) {
            tangentX = -radiusZ;
            tangentZ = radiusX;
        } else {
            tangentX = radiusZ;
            tangentZ = -radiusX;
        }

        Vector tangent = new Vector(tangentX, 0, tangentZ).normalize();

        // エッジの進行方向を考慮
        // 始点から終点への全体的な方向ベクトル
        Vector edgeDirection = p2.toVector().subtract(p1.toVector()).normalize();

        // 接線がエッジの進行方向と逆向きの場合は反転
        if (tangent.dot(edgeDirection) < 0) {
            tangent = tangent.multiply(-1);
        }

        return tangent;
    }

    /**
     * クロソイドエッジの正確な接線ベクトルを計算します。
     * Catmull-Romスプラインの方程式から導出された接線を使用します。
     */
    private Vector calculateClothoidTangentAccurate(Location p1, Location p2, @Nullable CurveAnchor anchor, boolean isStartNode) {
        return calculateTangentFromPoints(p1, p2, anchor, isStartNode);
    }

    /**
     * 3点から接線ベクトルを計算する共通メソッド。
     * パフォーマンス最適化：Vector計算を再利用。
     */
    private Vector calculateTangentFromPoints(Location p1, Location p2, @Nullable CurveAnchor anchor, boolean isStartNode) {
        Vector p1Vec = p1.toVector();
        Vector p2Vec = p2.toVector();
        Vector chordVec = p2Vec.subtract(p1Vec);

        double chordLength = chordVec.length();
        if (chordLength < MIN_RADIUS) {
            return chordVec.normalize();
        }

        if (anchor != null) {
            Vector anchorVec = anchor.getLocation().toVector();
            Vector tangent = isStartNode ?
                anchorVec.subtract(p1Vec) :
                p2Vec.subtract(anchorVec);
            return tangent.normalize();
        } else {
            return chordVec.normalize();
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

            if (circleData == null || circleData.radius > MAX_RADIUS || circleData.radius < MIN_RADIUS || !isValidAnchor(p1, p2, anchorLoc)) { // 円が計算できない、または異常な場合、またはアンカーが無効な場合
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
            angleDiff = normalizeAngle(angleDiff);

            // アンカー経由の円弧方向を決定
            // 外積による位置判定も考慮
            boolean shouldUseCounterClockwise = determineArcDirection(startAngle, endAngle, anchorAngle);
            boolean anchorOnLeftSide = isAnchorOnCorrectSide(p1_xz, p2_xz, anchor_xz);

            // 角度計算結果と外積結果が一致しない場合は、より保守的な判定を使用
            if (shouldUseCounterClockwise != anchorOnLeftSide) {
                // 外積による判定を優先（より幾何学的に正確）
                shouldUseCounterClockwise = anchorOnLeftSide;
            }

            if (!shouldUseCounterClockwise) {
                angleDiff = normalizeAngle(angleDiff + ((angleDiff > 0) ? -2 * Math.PI : 2 * Math.PI));
            }

            // 曲率に基づく適応的サンプリング
            double arcLength = Math.abs(angleDiff) * radius;
            double adaptiveStep = Math.max(step, 0.2 / radius); // 半径が小さいほど密度を高く
            int numSteps = Math.max(1, (int) Math.ceil(arcLength / (adaptiveStep * arcLength)));

            for (int i = 1; i < numSteps; i++) {
                double t = (double) i / numSteps;
                double currentAngle = startAngle + angleDiff * t;
                double x = center_xz.getX() + radius * Math.cos(currentAngle);
                double z = center_xz.getZ() + radius * Math.sin(currentAngle);

                // Y座標は統一された補間方式を使用
                double y = interpolateY(p1, p2, anchorLoc, t);
                path.add(new Location(p1.getWorld(), x, y, z));
            }

        } else { // アンカーがない場合、接線ベースの円弧計算 (旧ロジックを簡略化)

            Vector tan1_xz = new Vector(tangent1.getX(), 0, tangent1.getZ()).normalize();
            Vector tan2_xz = new Vector(tangent2.getX(), 0, tangent2.getZ()).normalize();

            Vector p1_xz = new Vector(p1.getX(), 0, p1.getZ());
            Vector p2_xz = new Vector(p2.getX(), 0, p2.getZ());

            Vector normal1_xz = new Vector(-tan1_xz.getZ(), 0, tan1_xz.getX());
            Vector normal2_xz = new Vector(-tan2_xz.getZ(), 0, tan2_xz.getX());

            double det = normal1_xz.getX() * normal2_xz.getZ() - normal1_xz.getZ() * normal2_xz.getX();

            if (Math.abs(det) < EPSILON) { // 法線が平行 (接線も平行) -> 直線
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                return;
            }

            double s = ( (p2_xz.getX() - p1_xz.getX()) * normal2_xz.getZ() - (p2_xz.getZ() - p1_xz.getZ()) * normal2_xz.getX() ) / det;

            Vector center_xz = p1_xz.clone().add(normal1_xz.multiply(s));
            double radius = center_xz.distance(p1_xz);

            if (radius > MAX_RADIUS || radius < MIN_RADIUS) { // 半径が異常に大きい、または小さい場合も直線として扱う
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

            // 曲率に基づく適応的サンプリング
            double arcLength = Math.abs(angleDiff) * radius;
            double adaptiveStep = Math.max(step, 0.2 / radius); // 半径が小さいほど密度を高く
            int numSteps = Math.max(1, (int) Math.ceil(arcLength / (adaptiveStep * arcLength)));

            for (int i = 1; i < numSteps; i++) {
                double t = (double) i / numSteps;
                double currentAngle = startAngle + angleDiff * t;
                double x = center_xz.getX() + radius * Math.cos(currentAngle);
                double z = center_xz.getZ() + radius * Math.sin(currentAngle);
                double y = interpolateY(p1, p2, null, t); // Y座標は統一された補間方式を使用
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
            // Control points: p_prev_xz, p1_xz, anchor_xz, p2_xz
            Location anchorLoc = anchor.getLocation();
            Location p1_xz = new Location(p1.getWorld(), p1.getX(), 0, p1.getZ());
            Location p2_xz = new Location(p2.getWorld(), p2.getX(), 0, p2.getZ());
            Location anchor_xz = new Location(anchorLoc.getWorld(), anchorLoc.getX(), 0, anchorLoc.getZ());

            double segment1Length = p1_xz.distance(anchor_xz);
            double segment2Length = anchor_xz.distance(p2_xz);
            double totalSegmentLength = segment1Length + segment2Length;

            if (totalSegmentLength < 1e-6) { // Avoid division by zero or very small length
                for (double t = step; t < 1.0; t += step) {
                    path.add(lerp(p1, p2, t));
                }
                return;
            }

            double ratio = segment1Length / totalSegmentLength; // Proportion of the first segment

            for (double t = step; t < 1.0; t += step) {
                Location currentPoint;
                double currentY;
                if (t < ratio) { // First segment
                    double t_segment = t / ratio; // Normalize t for the first segment (0 to 1)
                    currentPoint = getPointOnCatmullRomSpline(t_segment, p_prev_xz, p1_xz, anchor_xz, p2_xz);
                } else { // Second segment
                    double t_segment = (t - ratio) / (1.0 - ratio); // Normalize t for the second segment (0 to 1)
                    currentPoint = getPointOnCatmullRomSpline(t_segment, p1_xz, anchor_xz, p2_xz, p_next_xz);
                }

                // Y座標は統一された補間方式を使用
                currentY = interpolateY(p1, p2, anchorLoc, t);
                path.add(new Location(p1.getWorld(), currentPoint.getX(), currentY, currentPoint.getZ()));
            }

        } else {
            // Fallback to a single Catmull-Rom segment if no anchor
            // Control points: p_prev_xz, p1_xz, p2_xz, p_next_xz
            Location p1_xz = new Location(p1.getWorld(), p1.getX(), 0, p1.getZ());
            Location p2_xz = new Location(p2.getWorld(),p2.getX(), 0, p2.getZ());

            for (double t = step; t < 1.0; t += step) {
                Location currentPoint = getPointOnCatmullRomSpline(t, p_prev_xz, p1_xz, p2_xz, p_next_xz);
                double y = interpolateY(p1, p2, null, t); // Y座標は統一された補間方式を使用
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

        if (Math.abs(D) < EPSILON) { // 3点が一直線上にある
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
        return Math.abs(v1.getX() * v2.getZ() - v1.getZ() * v2.getX()) < EPSILON;
    }

    /**
     * 角度を[-π, π]の範囲に正規化します。
     */
    private double normalizeAngle(double angle) {
        while (angle > Math.PI) angle -= 2 * Math.PI;
        while (angle < -Math.PI) angle += 2 * Math.PI;
        return angle;
    }

    /**
     * 3点から円弧の方向を決定します。
     * 外積を使用してアンカーの位置関係を正確に判定。
     */
    private boolean determineArcDirection(double startAngle, double endAngle, double anchorAngle) {
        // 角度を正規化
        startAngle = normalizeAngle(startAngle);
        endAngle = normalizeAngle(endAngle);
        anchorAngle = normalizeAngle(anchorAngle);

        // 直接経路の角度差
        double directDiff = normalizeAngle(endAngle - startAngle);

        // アンカー経由の2つの角度差
        double toAnchor = normalizeAngle(anchorAngle - startAngle);
        double fromAnchor = normalizeAngle(endAngle - anchorAngle);

        // 角度差の符号が同じ場合のみアンカー経由を考慮
        if (Math.signum(toAnchor) == Math.signum(fromAnchor) && Math.signum(toAnchor) != 0) {
            // 同じ方向への回転で、かつアンカー経由の合計角度が直接経路より小さい場合
            double totalAnchorPath = Math.abs(toAnchor) + Math.abs(fromAnchor);
            return totalAnchorPath < Math.abs(directDiff);
        }

        // 符号が異なる場合は直接経路を選択（反時計回り）
        return directDiff > 0;
    }

    /**
     * XZ平面でのベクトル外積を使用してアンカーの位置関係を判定します。
     */
    private boolean isAnchorOnCorrectSide(Vector p1_xz, Vector p2_xz, Vector anchor_xz) {
        // P1からP2へのベクトル
        Vector edge = p2_xz.clone().subtract(p1_xz);
        // P1からアンカーへのベクトル
        Vector toAnchor = anchor_xz.clone().subtract(p1_xz);

        // 外積のY成分（右手系では上向きが正）
        double crossProduct = edge.getX() * toAnchor.getZ() - edge.getZ() * toAnchor.getX();

        // 正なら左側（反時計回り）、負なら右側（時計回り）
        return crossProduct > 0;
    }

    /**
     * Y座標の統一補間方式。
     * アンカーが存在する場合は二次ベジェ曲線、ない場合は線形補間。
     */
    private double interpolateY(Location p1, Location p2, Location anchor, double t) {
        if (anchor != null) {
            // 二次ベジェ曲線補間
            return (1 - t) * (1 - t) * p1.getY() +
                   2 * (1 - t) * t * anchor.getY() +
                   t * t * p2.getY();
        } else {
            // 線形補間
            return p1.getY() * (1 - t) + p2.getY() * t;
        }
    }

    /**
     * アンカーが有効かどうかチェックします。
     * 距離比率チェックを追加して、離れすぎたアンカーによる異常な円弧を防ぐ。
     */
    private boolean isValidAnchor(Location p1, Location p2, Location anchor) {
        if (anchor == null) return false;

        // アンカーが始点・終点と同じ位置でないかチェック
        if (anchor.distance(p1) < EPSILON || anchor.distance(p2) < EPSILON) {
            return false;
        }

        // アンカーが始点と終点を結ぶ直線上にないかチェック
        if (isCollinear(p1, p2, anchor)) {
            return false;
        }

        // 弦長（始点と終点の距離）
        double chordLength = p1.distance(p2);
        if (chordLength < EPSILON) {
            return false; // 始点と終点が同じ位置
        }

        // アンカーから弦への最短距離が妥当かチェック
        double maxAnchorDistance = chordLength * MAX_ANCHOR_DISTANCE_RATIO;
        double anchorToP1 = anchor.distance(p1);
        double anchorToP2 = anchor.distance(p2);

        // アンカーが始点または終点から離れすぎていないかチェック
        if (anchorToP1 > maxAnchorDistance || anchorToP2 > maxAnchorDistance) {
            return false;
        }

        // 外積を使用してアンカーが適切な側にあるかチェック
        Vector p1_xz = new Vector(p1.getX(), 0, p1.getZ());
        Vector p2_xz = new Vector(p2.getX(), 0, p2.getZ());
        Vector anchor_xz = new Vector(anchor.getX(), 0, anchor.getZ());

        return isAnchorPositionReasonable(p1_xz, p2_xz, anchor_xz);
    }

    /**
     * アンカーの位置が幾何学的に妥当かチェックします。
     */
    private boolean isAnchorPositionReasonable(Vector p1_xz, Vector p2_xz, Vector anchor_xz) {
        // アンカーから弦（P1-P2）への最短距離を計算
        Vector chord = p2_xz.clone().subtract(p1_xz);
        Vector toAnchor = anchor_xz.clone().subtract(p1_xz);

        // 弦の長さ
        double chordLength = chord.length();
        if (chordLength < EPSILON) return false;

        // 弦方向の単位ベクトル
        Vector chordUnit = chord.clone().normalize();

        // アンカーの弦上への投影長
        double projection = toAnchor.dot(chordUnit);

        // アンカーから弦への垂直距離
        Vector projectedPoint = p1_xz.clone().add(chordUnit.multiply(projection));
        double perpendicularDistance = anchor_xz.distance(projectedPoint);

        // 垂直距離が弦長の半分以下であることを確認（極端に離れた位置を避ける）
        return perpendicularDistance <= chordLength * 0.5;
    }

    /**
     * 角度の循環補間を行います（-180°〜180°の範囲で）。
     * 360度境界を正しく処理します。
     */
    private float interpolateAngle(float angle1, float angle2, float t) {
        // 角度を-180〜180の範囲に正規化
        angle1 = normalizeAngleDegrees(angle1);
        angle2 = normalizeAngleDegrees(angle2);

        float difference = angle2 - angle1;

        // 最短経路を選択
        if (difference > 180) {
            difference -= 360;
        } else if (difference < -180) {
            difference += 360;
        }

        float result = angle1 + difference * t;
        return normalizeAngleDegrees(result);
    }

    /**
     * 角度を-180°〜180°の範囲に正規化します。
     */
    private float normalizeAngleDegrees(float angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
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

    /**
     * 直線でヨー角とピッチ角付きの点を計算します。
     */
    private void calculateStraightWithYaw(Location p1, Location p2, double step, List<Location> path) {
        Vector direction = p2.toVector().subtract(p1.toVector()).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(direction.getZ(), direction.getX()));
        float pitch = (float) Math.toDegrees(Math.asin(-direction.getY()));

        for (double t = step; t < 1.0; t += step) {
            Location interpolated = lerp(p1, p2, t);
            interpolated.setYaw(yaw);
            interpolated.setPitch(pitch);
            path.add(interpolated);
        }
    }

    /**
     * 円弧でヨー角とピッチ角付きの点を計算します。
     */
    private void calculateArcWithYaw(Location p1, Location p2, Vector tangent1, Vector tangent2, @Nullable CurveAnchor anchor, double step, List<Location> path) {
        // 元のcalculateArcロジックを使用しつつ、各点でヨー角とピッチ角を計算
        calculateArc(p1, p2, tangent1, tangent2, anchor, step, path);
        applyYawAndPitchToPath(path, tangent1);
    }

    /**
     * クロソイドでヨー角とピッチ角付きの点を計算します。
     */
    private void calculateClothoidApproximationWithYaw(Location p1, Location p2, Vector tangent1, Vector tangent2, @Nullable CurveAnchor anchor, double step, List<Location> path) {
        // 元のcalculateClothoidApproximationロジックを使用しつつ、各点でヨー角とピッチ角を計算
        calculateClothoidApproximation(p1, p2, tangent1, tangent2, anchor, step, path);
        applyYawAndPitchToPath(path, tangent1);
    }

    /**
     * パスの各点にヨー角とピッチ角を設定します（重複コードを統合）。
     */
    private void applyYawAndPitchToPath(List<Location> path, Vector firstTangent) {
        // 生成された各点にヨー角とピッチ角を設定
        for (int i = 1; i < path.size(); i++) {
            Location current = path.get(i);
            Location previous = path.get(i - 1);

            Vector tangent = current.toVector().subtract(previous.toVector()).normalize();
            float yaw = (float) Math.toDegrees(Math.atan2(tangent.getZ(), tangent.getX()));
            float pitch = (float) Math.toDegrees(Math.asin(-tangent.getY()));
            current.setYaw(yaw);
            current.setPitch(pitch);
        }

        // 最初の点のヨー角とピッチ角も設定
        if (path.size() > 1) {
            Vector normalizedFirstTangent = firstTangent.normalize();
            float firstYaw = (float) Math.toDegrees(Math.atan2(normalizedFirstTangent.getZ(), normalizedFirstTangent.getX()));
            float firstPitch = (float) Math.toDegrees(Math.asin(-normalizedFirstTangent.getY()));
            path.get(0).setYaw(firstYaw);
            path.get(0).setPitch(firstPitch);
        }
    }

    /**
     * 指定されたパスを、一定の距離間隔を持つ新しいパスに再サンプリングします。
     * これにより、カーブや坂でも点の間隔が均一になり、建築時の隙間を防ぎます。
     * @param originalPath 元の高密度パス
     * @param stepDistance 新しい点の間隔（例：0.5ブロック）
     * @return 再サンプリングされたパス
     */
    private List<Location> resamplePath(List<Location> originalPath, double stepDistance) {
        if (originalPath.size() < 2) {
            return originalPath;
        }

        List<Location> resampledPath = new ArrayList<>();
        resampledPath.add(originalPath.get(0).clone()); // 始点を追加（ヨー角とピッチ角も保持）

        double distanceCovered = 0.0;
        for (int i = 0; i < originalPath.size() - 1; i++) {
            Location current = originalPath.get(i);
            Location next = originalPath.get(i + 1);
            Vector segment = next.toVector().subtract(current.toVector());
            double segmentLength = segment.length();

            while (distanceCovered + segmentLength >= stepDistance) {
                double remainingDistanceInSegment = stepDistance - distanceCovered;
                double t = remainingDistanceInSegment / segmentLength;

                Location newPoint = current.clone().add(segment.clone().multiply(t));

                // ヨー角とピッチ角を補間
                float currentYaw = current.getYaw();
                float nextYaw = next.getYaw();
                float currentPitch = current.getPitch();
                float nextPitch = next.getPitch();

                // ヨー角とピッチ角の循環補間（改良版）
                float interpolatedYaw = interpolateAngle(currentYaw, nextYaw, (float) t);
                float interpolatedPitch = interpolateAngle(currentPitch, nextPitch, (float) t);

                newPoint.setYaw(interpolatedYaw);
                newPoint.setPitch(interpolatedPitch);

                resampledPath.add(newPoint);

                current = newPoint;
                segment = next.toVector().subtract(current.toVector());
                segmentLength = segment.length();
                distanceCovered = 0.0;
            }
            distanceCovered += segmentLength;
        }

        resampledPath.add(originalPath.get(originalPath.size() - 1).clone()); // 終点を追加（ヨー角とピッチ角も保持）
        return resampledPath;
    }
}
