package com.fkhwl.nfs.biz.entity.dto.visual;

/**
 * 时间窗口部分检索请求（POST /trajectory/search-by-time）。
 * startTime/endTime 采用 "yyyy-MM-dd HH:mm:ss"（北京时间，与 gtm 一致）。
 */
public class WindowSearchForm {
    public Long waybillId;
    public String startTime;
    public String endTime;
}
