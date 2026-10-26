package com.yanyuetong.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 跨组件预约请求状态：PRE_ACCEPTED 表示已建账，PUBLISHED 表示 broker 确认收到，
 * UNKNOWN 表示投递结果无法确定，CONFIRMED/COMPENSATED 分别表示已确认预约事实/无需继续创建预约。
 */
public enum ReservationRequestStatus {
    /** 已创建请求账本，Redis 可能还没有预占；异常中断后需要对账判定。 */
    PRE_ACCEPTED,
    /** 发布已获 broker 确认且未退回，仍需等待异步消费者创建预约事实。 */
    PUBLISHED,
    /** 消费或对账已确认预约事实，不是批次发布状态，也不是履约核验完成状态。 */
    CONFIRMED,
    /** 发送超时或中断等导致结果不明，保留预占，交由消费或对账收敛。 */
    UNKNOWN,
    /** 请求已终结，无需再落库；既包含预占后回滚，也包含预占被拒、无需回滚的情况。 */
    COMPENSATED;

    private static final List<ReservationRequestStatus> NON_TERMINAL_STATUSES =
            Collections.unmodifiableList(Arrays.asList(PRE_ACCEPTED, PUBLISHED, UNKNOWN));

    public boolean isTerminal() {
        return this == CONFIRMED || this == COMPENSATED;
    }

    public static List<ReservationRequestStatus> nonTerminalStatuses() {
        return NON_TERMINAL_STATUSES;
    }
}
