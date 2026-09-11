package com.logicompress.experiment.baseline;

import com.logicompress.experiment.clean.CleanedTrack;
import com.logicompress.experiment.compress.DpUtil;

import java.util.List;

/**
 * 纯 DP（Douglas-Peucker）：固定容差，无任何语义信息。
 * 距离口径对齐公司 GisDouglasUtil（Haversine + 海伦公式），按断线分段执行。
 */
public class PlainDp implements LossyBaseline {
    @Override
    public List<Integer> compress(CleanedTrack cleaned, double tolM) {
        return DpUtil.dpOnSegments(cleaned, tolM);
    }
}
