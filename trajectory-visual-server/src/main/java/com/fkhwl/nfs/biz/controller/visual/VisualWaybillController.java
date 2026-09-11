package com.fkhwl.nfs.biz.controller.visual;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fkhwl.nfs.biz.entity.form.visual.WaybillQueryForm;
import com.fkhwl.nfs.biz.entity.po.TrajectoryWaybill;
import com.fkhwl.nfs.biz.service.visual.VisualWaybillService;
import com.fkhwl.nfs.common.PageResult;
import com.fkhwl.nfs.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运单查询（页面一 轨迹压缩工作台 的运单列表）。
 * 检索字段：运单号/车辆id/车牌(数据源缺失故常空)/时间范围/压缩状态，分页。
 */
@Tag(name = "可视化-运单")
@RestController
@RequestMapping("/api/visual/waybill")
public class VisualWaybillController {

    private final VisualWaybillService waybillService;

    public VisualWaybillController(VisualWaybillService waybillService) {
        this.waybillService = waybillService;
    }

    @Operation(summary = "运单分页查询")
    @GetMapping("/page")
    public Result<PageResult<TrajectoryWaybill>> page(@ModelAttribute WaybillQueryForm form) {
        IPage<TrajectoryWaybill> page = waybillService.page(form);
        return Result.ok(new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords()));
    }

    @Operation(summary = "运单详情")
    @GetMapping("/detail/{waybillId}")
    public Result<TrajectoryWaybill> detail(@PathVariable Long waybillId) {
        return Result.ok(waybillService.getByWaybillId(waybillId));
    }

    @Operation(summary = "导入运单元数据（从源目录扫描，admin）",
            description = "扫描 trajectory.source.full-data-dir 下 track_*.json，按 waybill_id 去重 upsert 元数据。limit>0 覆盖配置上限。")
    @PostMapping("/admin/import-waybills")
    public Result<Map<String, Object>> importWaybills(@RequestParam(required = false) Integer limit) {
        return Result.ok(waybillService.importFromSource(limit));
    }
}
