package com.hmdp.enums;

import java.time.LocalDateTime;

public enum BookingState {
    DRAFT,
    NOT_STARTED,
    OPEN,
    EXPIRED,
    CLOSED,
    CANCELLED;

    public static BookingState of(ReservationBatchStatus status,
                                  LocalDateTime bookingStartTime,
                                  LocalDateTime bookingEndTime,
                                  LocalDateTime now) {
        if (status == ReservationBatchStatus.DRAFT) {
            return DRAFT;
        }
        if (status == ReservationBatchStatus.CLOSED) {
            return CLOSED;
        }
        if (status == ReservationBatchStatus.CANCELLED) {
            return CANCELLED;
        }
        if (now.isBefore(bookingStartTime)) {
            return NOT_STARTED;
        }
        if (!now.isAfter(bookingEndTime)) {
            return OPEN;
        }
        return EXPIRED;
    }
}
