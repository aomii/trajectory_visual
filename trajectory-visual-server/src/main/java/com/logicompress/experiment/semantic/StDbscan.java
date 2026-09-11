package com.logicompress.experiment.semantic;

import com.logicompress.experiment.geo.GeoUtil;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L2 时空聚类（种子引导 ST-DBSCAN）：
 * - 时空邻域：d_s ≤ epsM 且 |t_i − t_j| ≤ epsTS
 * - 种子优先：先扩张种子点（L1 围栏命中点）避免业务停留被噪声干扰，再聚类其余点
 * - 空间网格索引控制复杂度
 * 论文 claude_09 3.4：ε / εt / MinPts 由实验定标，默认 50m / 300s / 3。
 */
public class StDbscan {

    /** 聚类结果：clusterId[i] = 簇号（-1=噪声） */
    public final int[] clusterId;
    /** 每个簇的点下标 */
    public final List<List<Integer>> clusters;

    private StDbscan(int n, List<List<Integer>> clusters) {
        this.clusterId = new int[n];
        Arrays.fill(this.clusterId, -1);
        this.clusters = clusters;
    }

    /**
     * 种子引导 ST-DBSCAN 聚类（论文 3.4 L2）：
     * 时空邻域 = 空间距离 ≤ epsM 且时间差 ≤ epsTS 的点集；邻域点数 ≥ minPts 判为核心点。
     * 关键设计——两遍扫描：第 1 遍只扩张种子点（seeds = L1 围栏命中点），第 2 遍处理其余点，
     * 保证业务停留（装卸货）优先成簇、不被后续噪声干扰。
     * 空间用网格索引（格子边长 epsM）把邻域查询限制在相邻 3×3 格，控制复杂度为近线性。
     *
     * @param pts   清洗后轨迹点
     * @param epsM  空间邻域半径（米）
     * @param epsTS 时间邻域半径（秒）
     * @param minPts 最小簇点数
     * @param seeds L1 围栏命中点下标（优先扩张）
     * @return clusterId[i] = 第 i 点簇号（-1 = 噪声），clusters = 各簇点下标集合
     */
    public static StDbscan cluster(List<TrackPoint> pts, double epsM, long epsTS, int minPts,
                                   Set<Integer> seeds) {
        int n = pts.size();                                  // n = 轨迹点总数
        double[] x = new double[n];                          // x[i] = 第 i 点的平面横坐标（米）
        double[] y = new double[n];                          // y[i] = 第 i 点的平面纵坐标（米）
        // 经纬度是球面坐标，不能直接算欧氏距离，这里以第 0 个点为原点做局部平面投影。
        // GeoUtil.project(纬度, 经度, 原点纬度, 原点经度) 返回 double[]{x, y}，单位米。
        for (int i = 0; i < n; i++) {
            double[] xy = GeoUtil.project(pts.get(i).lat, pts.get(i).lon, pts.get(0).lat, pts.get(0).lon);
            x[i] = xy[0];                                    // 取出投影后的 x
            y[i] = xy[1];                                    // 取出投影后的 y
        }

        // ========== 建空间网格索引（加速邻域查询） ==========
        // 思想：把平面切成边长 = epsM 的小方格，每个点按坐标落入某一格。
        // 之后查"点 i 的邻居"只需看 i 所在格及其相邻 3×3 格，不用和全部点比对，复杂度从 O(n²) 降到近线性。
        long cell = Math.max(1L, (long) epsM);               // 格子边长 = epsM(50m)；强转 long 截断小数，(long)epsM 可能为 0，用 Math.max 兜底为 1
        Map<Long, List<Integer>> grid = new HashMap<>();     // 泛型嵌套：键=格子键(long)，值=该格子里的点下标列表
        for (int i = 0; i < n; i++) {
            // computeIfAbsent(键, lambda)：哈希表里没有这个键时，才用 lambda 创建一个新 ArrayList 并放进表里；
            // 已存在则直接返回原列表。k -> new ArrayList<>() 是 lambda 表达式，k 是传入的键（此处没用上）。
            // 然后对返回的列表 .add(i)，把当前点下标塞进它所属的格子。这行 = "取格子列表，没有就建，再追加点"。
            grid.computeIfAbsent(cellKey(x[i], y[i], cell), k -> new ArrayList<>()).add(i);
        }

        // neighborCache：邻域结果缓存。neighborCache.get(i) 存点 i 的邻居列表，算过一次就不重算。
        // 泛型 List<List<Integer>> 读作"列表的列表"：外层按点下标取值，内层是该点的邻居下标列表。
        List<List<Integer>> neighborCache = new ArrayList<>(n);  // 预分配 n 个位置
        for (int i = 0; i < n; i++) neighborCache.add(null);    // 先用 null 占位，表示"还没算过"

        boolean[] visited = new boolean[n];                  // visited[i]=true 表示点 i 的邻域已查询过（防重复 regionQuery）
        boolean[] inCluster = new boolean[n];                // inCluster[i]=true 表示点 i 已属于某个簇
        int clusterNo = 0;                                   // 簇计数（仅计数用）
        List<List<Integer>> clusters = new ArrayList<>();    // 收集所有簇，每个簇是一个点下标列表

        // ========== 两遍扫描（种子优先的核心设计） ==========
        // pass=0 只让种子点（L1 围栏命中点）开簇，pass=1 才轮到其余点。
        // 目的：业务停留（装卸货）密度高，先让它们成簇"抢占"高密区域，不被后续噪声干扰。
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                boolean isSeed = seeds.contains(i);          // seeds 是 Set<Integer>，i 是 int，会自动装箱(Integer)再查
                if (pass == 0 && !isSeed) continue;          // 第 0 遍：非种子直接跳过
                if (pass == 1 && isSeed) continue;           // 第 1 遍：种子跳过（上一遍已处理完）
                if (visited[i]) continue;                    // 已被 BFS 扩张过/邻域查过 → 跳过
                visited[i] = true;                           // 标记：邻域查询将执行（或已执行）
                // 查时空邻域：返回所有"距离≤epsM 且 时间差≤epsTS"的点下标（含自己）
                List<Integer> neighbors = regionQuery(i, x, y, cell, epsM, epsTS, pts, grid, neighborCache);
                if (neighbors.size() >= minPts) {            // 邻居够多 → i 是核心点，可以开新簇
                    List<Integer> cluster = new ArrayList<>();   // 新建一个簇
                    expandCluster(i, neighbors, cluster, visited, inCluster, x, y, cell, epsM, epsTS, minPts, pts, grid, neighborCache);
                    clusters.add(cluster);                   // 簇扩张完成后收进总集合
                    clusterNo++;                             // 簇号 +1
                }                                            // 邻居不够 → i 是噪声点，保持 clusterId=-1
            }
        }

        // ========== 回填结果 ==========
        StDbscan result = new StDbscan(n, clusters);         // 构造结果对象（构造函数里把 clusterId 全填 -1）
        for (int c = 0; c < clusters.size(); c++) {          // c = 簇号（0 起）
            for (int idx : clusters.get(c)) result.clusterId[idx] = c;  // 把簇内每个点标上簇号 c
        }                                                    // 没标到的点保持 -1（噪声）
        return result;                                       // 返回：clusterId[i] + clusters
    }

    /**
     * 从核心点 seed 出发的 BFS 簇扩张（标准 DBSCAN）：
     * 把 seed 的邻域点全部并入当前簇；邻域中若某点仍是核心点则继续扩张它的邻域，
     * 否则作为边界点仅标记入簇、不再扩张。
     * 状态区分：visited=该点邻域已查询过（避免重复 regionQuery）；inCluster=已在某簇中。
     */
    private static void expandCluster(int seed, List<Integer> neighbors, List<Integer> cluster,
                                      boolean[] visited, boolean[] inCluster, double[] x, double[] y,
                                      long cell, double epsM, long epsTS, int minPts, List<TrackPoint> pts,
                                      Map<Long, List<Integer>> grid, List<List<Integer>> neighborCache) {
        cluster.add(seed);                                   // 核心点自己先进簇
        inCluster[seed] = true;                              // 标记已入簇
        // 队列 = 核心点的全部邻居。下面用"下标递增的 for"而不是迭代器，
        // 是因为队列在循环中还会不断追加新点，queue.size() 每轮都会重新求值 → 实现 BFS 的动态队列。
        List<Integer> queue = new ArrayList<>(neighbors);
        for (int k = 0; k < queue.size(); k++) {             // 注意：size() 每轮重新读，新点会继续被遍历
            int q = queue.get(k);                            // 取出队头点
            if (inCluster[q]) continue;                      // 已入簇 → 跳过（防重复）
            inCluster[q] = true;                             // 标记入簇
            cluster.add(q);                                  // 并入当前簇
            if (visited[q]) continue;                        // 该点邻域查过了，不再查（避免重复计算）
            visited[q] = true;                               // 标记：即将查询它的邻域
            List<Integer> qn = regionQuery(q, x, y, cell, epsM, epsTS, pts, grid, neighborCache);  // 查 q 的邻域
            if (qn.size() >= minPts) {                       // q 也是核心点 → 它的邻居能继续扩张
                for (int nb : qn) {                          // 遍历 q 的每个邻居
                    if (!inCluster[nb]) queue.add(nb);       // 没入簇的 → 入队，后面会被并进簇
                }                                            // 已入簇的跳过，不重复入队
            }                                                // 若 q 只是边界点（邻居<minPts），不再扩张
        }
    }

    /**
     * 时空邻域查询：返回点 i 的邻域点下标集合——
     * 只扫描 i 所在网格格及其相邻 3×3 格（按格子边长 cell≈epsM 分桶），
     * 过滤出空间距离 ≤ epsM 且时间差 ≤ epsTS 的点。结果按点缓存（neighborCache），
     * 每个点最多算一次。网格键用 (cx<<32)^cy 压缩成 long（key）。
     */
    private static List<Integer> regionQuery(int i, double[] x, double[] y, long cell,
                                             double epsM, long epsTS, List<TrackPoint> pts,
                                             Map<Long, List<Integer>> grid, List<List<Integer>> neighborCache) {
        List<Integer> cached = neighborCache.get(i);         // 取缓存：点 i 的邻居算过吗？
        if (cached != null) return cached;                   // 算过 → 直接返回，省掉重复计算
        List<Integer> res = new ArrayList<>();               // 结果列表：点 i 的所有时空邻居下标
        long cx = Math.floorDiv((long) x[i], cell);          // 点 i 所在格子的列号：floorDiv = 向下取整除法
        long cy = Math.floorDiv((long) y[i], cell);          // 行号。注意必须用 floorDiv 而非普通除法：
        long t = pts.get(i).gtmEpoch;                        // 负数坐标（向西/向南）时普通除法会向 0 截断，导致格子错位
        // 扫描 i 所在格及其周围 3×3 共 9 个格子：dx/dy 取 -1, 0, 1 是"上下左右各扩一格"
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                List<Integer> cellPts = grid.get(key(cx + dx, cy + dy));  // 取相邻格子的点列表
                if (cellPts == null) continue;               // 该格没点 → 跳过（HashMap 查不到返回 null）
                for (int j : cellPts) {                      // 逐个检查格子里的点 j
                    // 时间过滤：|t_j - t_i| 超过 epsTS → 不是邻域点，continue 跳过
                    if (Math.abs(pts.get(j).gtmEpoch - t) > epsTS) continue;
                    // 空间过滤：算欧氏距离平方 d2。故意不开平方根：
                    // 比较 d2 ≤ epsM² 与直接比较距离 ≤ epsM 等价，但省去昂贵的 sqrt，点数多时能省不少时间。
                    double d2 = (x[i] - x[j]) * (x[i] - x[j]) + (y[i] - y[j]) * (y[i] - y[j]);
                    if (d2 <= epsM * epsM) res.add(j);       // 距离 ≤ epsM → 收入邻域
                }
            }
        }
        neighborCache.set(i, res);                           // 结果写缓存，下次直接命中
        return res;                                          // 返回点 i 的时空邻域（含 i 自己，d2=0 必过）
    }

    /** 由点坐标算格子键：先算格子行列号，再压成 long 键 */
    private static long cellKey(double px, double py, long cell) {
        return key(Math.floorDiv((long) px, cell), Math.floorDiv((long) py, cell));
    }

    /**
     * 把两个格子坐标 (cx, cy) 压成一个 long 键 —— 位运算新语法：
     *   (cx << 32)        把 cx 左移 32 位，放进 long 的高 32 位；
     *   (cy & 0xffffffffL) 用掩码把 cy 截成无符号 32 位（0~2³²-1），防负数符号位污染；
     *   ^ 异或：高 32 位与低 32 位互不重叠，异或 = 按位拼合，得到唯一 long 键。
     * 优点：一个 long 存两个 int 坐标，比拼接字符串快得多，且 HashMap 用 long 做键无哈希冲突计算成本。
     */
    private static long key(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }
}
