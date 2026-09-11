package com.fkhwl.nfs.biz.service.visual;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fkhwl.nfs.biz.entity.form.visual.WaybillQueryForm;
import com.fkhwl.nfs.biz.entity.po.TrajectoryWaybill;

import java.util.Map;

/**
 * 运单元数据服务：从全量轨迹源目录导入元数据到 MySQL、分页/详情查询。
 * 原始轨迹点数组不落库，查看原始轨迹时按 sourceFile 现读。
 */
public interface VisualWaybillService {

    /**
     * 扫描源目录并导入运单元数据（按 waybill_id 去重 upsert）。
     *
     * @param limitOverride 本次导入上限（>0 覆盖配置）；null 用 trajectory.source.max-files-per-job
     * @return 统计：total / imported / updated / failed
     */
    Map<String, Object> importFromSource(Integer limitOverride);

    /** 分页查询运单 */
    IPage<TrajectoryWaybill> page(WaybillQueryForm form);

    /** 按 waybillId 查运单元数据 */
    TrajectoryWaybill getByWaybillId(long waybillId);
}
