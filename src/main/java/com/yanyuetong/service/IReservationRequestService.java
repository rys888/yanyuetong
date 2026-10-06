package com.yanyuetong.service;

import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationRequestStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface IReservationRequestService {

    /** 插入一条 PRE_ACCEPTED 账本行。requestId 已存在时抛异常。 */
    void createPreAccepted(long requestId, Long userId, Long batchId);

    /** 按 requestId 查账本；不存在返回 null。 */
    ReservationRequest getByRequestId(long requestId);

    /**
     * CAS 更新状态：仅当当前状态在 expectedStatuses 中时才置为 target，返回是否更新成功（影响行数 == 1）。
     * expectedStatuses 必须非空；为空时抛 IllegalArgumentException（避免退化成无条件覆盖）。
     */
    boolean casStatus(long requestId, ReservationRequestStatus target,
                      ReservationRequestStatus... expectedStatuses);

    /**
     * CAS 更新状态并同时写入 fail_reason：仅当当前状态在 expectedStatuses 中时才更新，
     * 返回是否更新成功（影响行数 == 1）。expectedStatuses 必须非空，为空抛 IllegalArgumentException。
     */
    boolean casStatusWithFailReason(long requestId, ReservationRequestStatus target, String failReason,
                                    ReservationRequestStatus... expectedStatuses);

    /** 仅更新 fail_reason，不改 status。返回是否更新成功。 */
    boolean recordFailReason(long requestId, String failReason);

    /**
     * 扫描「非终态 且 update_time 早于 before」的账本，按 update_time 升序，最多 limit 条。
     * 供对账使用。非终态集合取自 ReservationRequestStatus.nonTerminalStatuses()。
     */
    List<ReservationRequest> listStale(LocalDateTime before, int limit);
}
