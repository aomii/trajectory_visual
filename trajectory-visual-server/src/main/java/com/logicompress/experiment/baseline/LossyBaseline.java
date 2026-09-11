package com.logicompress.experiment.baseline;

import com.logicompress.experiment.clean.CleanedTrack;

import java.util.List;

/**
 * 有损层离线基线算法接口：输入清洗轨迹，输出压缩后保留的点下标。
 * 全部为离线（批次）算法，与在线算法（SQUISH/Dead Reckoning）区分。
 */
public interface LossyBaseline {
    List<Integer> compress(CleanedTrack cleaned, double tolM);
}
