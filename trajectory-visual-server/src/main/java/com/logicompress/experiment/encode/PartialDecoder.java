package com.logicompress.experiment.encode;

import com.logicompress.experiment.model.EncodeBlock;
import com.logicompress.experiment.model.TrackPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 部分解压（论文算法 4-4）：按时间窗 [t1,t2] 命中块（t_start≤t2 ∧ t_end≥t1），
 * 只读取命中块的 zip 字节并解压，边界块按点过滤，再按时间序拼接。无需全量解压。
 */
public class PartialDecoder {

    /** 一次按时间窗部分解压的结果 */
    public static class WindowResult {
        /** 解压出的窗口内轨迹点（时间升序，边界块已按 [t1,t2] 过滤） */
        public List<TrackPoint> points = new ArrayList<>();
        /** 命中的块数（块时间跨度与窗口相交的块） */
        public int blocksHit;
        /** 实际读取的命中块字节数（不含未命中块） */
        public int bytesRead;
    }

    public WindowResult decodeWindow(EncodedStore store, long t1, long t2, int precision) {
        WindowResult r = new WindowResult();
        for (EncodeBlock b : store.blocks) {
            if (b.tEnd < t1 || b.tStart > t2) continue;
            r.blocksHit++;
            r.bytesRead += b.length;
            byte[] z = Arrays.copyOfRange(store.blob, (int) b.byteOffset, (int) b.byteOffset + b.length);
            String ascii = BlockOffsetCodec.inflate(z);
            List<TrackPoint> blockPts = BlockOffsetCodec.decodeToPoints(ascii, precision);
            for (TrackPoint p : blockPts) {
                if (p.gtmEpoch >= t1 && p.gtmEpoch <= t2) {
                    r.points.add(p);
                }
            }
        }
        r.points.sort((a, b2) -> Long.compare(a.gtmEpoch, b2.gtmEpoch));
        return r;
    }

    /** 全量解压（对比基准） */
    public static List<TrackPoint> decodeAll(EncodedStore store, int precision) {
        List<TrackPoint> all = new ArrayList<>();
        for (EncodeBlock b : store.blocks) {
            byte[] z = Arrays.copyOfRange(store.blob, (int) b.byteOffset, (int) b.byteOffset + b.length);
            all.addAll(BlockOffsetCodec.decodeToPoints(BlockOffsetCodec.inflate(z), precision));
        }
        all.sort((a, b2) -> Long.compare(a.gtmEpoch, b2.gtmEpoch));
        return all;
    }
}
