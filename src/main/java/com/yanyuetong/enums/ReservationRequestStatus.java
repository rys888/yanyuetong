package com.yanyuetong.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum ReservationRequestStatus {
    PRE_ACCEPTED,
    PUBLISHED,
    CONFIRMED,
    UNKNOWN,
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
