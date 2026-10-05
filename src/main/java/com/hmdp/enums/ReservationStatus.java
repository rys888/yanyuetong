package com.hmdp.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum ReservationStatus {
    RESERVED,
    CANCELLED,
    COMPLETED,
    NO_SHOW;

    /**
     * 占用 (user_id, batch_id) 唯一键槽位的状态集合，判据必须与 tb_reservation.active_flag
     * 生成列严格一致：CASE WHEN status = 'CANCELLED' THEN NULL ELSE 1 END。
     * 因此除 CANCELLED 之外的状态都算占用 —— COMPLETED / NO_SHOW 的行仍占着槽位，
     * 该用户不能再对同一批次发起预约（与生成列拒绝插入的行为一致）。
     */
    private static final List<ReservationStatus> OCCUPYING_STATUSES =
            Collections.unmodifiableList(Arrays.asList(RESERVED, COMPLETED, NO_SHOW));

    public boolean isActive() {
        return this == RESERVED;
    }

    public boolean isTerminal() {
        return this != RESERVED;
    }

    public static List<ReservationStatus> occupyingStatuses() {
        return OCCUPYING_STATUSES;
    }
}
