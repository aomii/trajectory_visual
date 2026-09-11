package com.logicompress.experiment.encode;

import com.logicompress.experiment.config.ExperimentConfig;
import com.logicompress.experiment.model.EncodeBlock;
import com.logicompress.experiment.model.TrackPoint;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 分块编码器：把抽稀后的点序列按固定时间窗切块（块长由 {@code cfg.blockWindowS} 决定，
 * 可视化系统当前口径为 3600s=1h），
 * 每块独立编码（块首绝对坐标、块内相对差分）→ zip，拼接为 blob，并建立稀疏时间索引。
 * 论文 claude_06 3.2 / claude_10 4.3、4.4。
 */
public class BlockEncoder {

    /** @param compressedIdx 抽稀后保留的点在 cleaned 中的下标（时间升序） */
    public EncodedStore encode(List<TrackPoint> cleaned, List<Integer> compressedIdx,
                               Set<Integer> anchors, ExperimentConfig cfg) {
        EncodedStore store = new EncodedStore();
        store.sourceIndices = compressedIdx;

        long trackStart = cleaned.get(compressedIdx.get(0)).gtmEpoch;
        // 按块聚合下标
        List<List<Integer>> buckets = new ArrayList<>();
        List<Long> bucketStart = new ArrayList<>();
        for (int idx : compressedIdx) {
            TrackPoint p = cleaned.get(idx);
            long blockId = (p.gtmEpoch - trackStart) / cfg.blockWindowS;
            while (buckets.size() <= blockId) {
                buckets.add(new ArrayList<>());
                bucketStart.add(trackStart + buckets.size() * cfg.blockWindowS);
            }
            buckets.get((int) blockId).add(idx);
        }

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        store.totalAsciiBytes = 0;
        int chunkId = 0;
        for (int b = 0; b < buckets.size(); b++) {
            List<Integer> idxs = buckets.get(b);
            if (idxs.isEmpty()) continue;
            EncodeBlock block = new EncodeBlock();
            block.chunkId = chunkId;
            block.points = new ArrayList<>();
            boolean hasKey = false;
            for (int idx : idxs) {
                TrackPoint p = cleaned.get(idx);
                block.points.add(p);
                if (anchors.contains(idx)) hasKey = true;
            }
            block.tStart = block.points.get(0).gtmEpoch;
            block.tEnd = block.points.get(block.points.size() - 1).gtmEpoch;
            block.pointCount = block.points.size();
            block.hasKeyPoint = hasKey;

            String ascii = BlockOffsetCodec.encodePoints(block.points, cfg.precision);
            block.ascii = ascii;
            store.totalAsciiBytes += ascii.length();

            byte[] zipped = BlockOffsetCodec.deflate(ascii, cfg.zipLevel);
            block.byteOffset = blob.size();
            blob.write(zipped, 0, zipped.length);
            block.length = zipped.length;
            store.blocks.add(block);
            chunkId++;
        }
        store.blob = blob.toByteArray();
        return store;
    }
}
