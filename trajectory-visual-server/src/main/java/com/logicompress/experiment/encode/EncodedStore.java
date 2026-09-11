package com.logicompress.experiment.encode;

import com.logicompress.experiment.model.EncodeBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块编码后的存储：zip 后块负载拼接的 blob + 稀疏时间索引。
 * 支持按时间窗部分解压（只读命中块的字节）。
 */
public class EncodedStore {
    /** 所有块的 zip 后负载拼接 */
    public byte[] blob;
    /** 稀疏时间索引（每块一行） */
    public List<EncodeBlock> blocks = new ArrayList<>();
    /** 编码前 ASCII 总字节数（zip 前） */
    public long totalAsciiBytes;
    /** 编码前原始轨迹点序列（抽稀后、编码前），供对比 */
    public java.util.List<Integer> sourceIndices;

    public int totalZippedBytes() {
        return blob == null ? 0 : blob.length;
    }
}
