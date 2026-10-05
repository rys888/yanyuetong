package com.hmdp.service;

import com.hmdp.dto.ReconcileSummaryDTO;

public interface IReservationReconcileService {

    /**
     * 对账最多 limit 条长期未完成的预约请求并返回处理摘要。
     * 本方法不加事务、不获取分布式锁，可由测试或人工任务直接调用。
     * 已落库分支只推进状态，不清空 fail_reason；该字段有意保留最近一次失败的审计线索。
     */
    ReconcileSummaryDTO reconcile(int limit);
}
