package com.yanyuetong.service;

import com.yanyuetong.enums.ReservationCancelOutcome;

import java.time.LocalDateTime;

public interface IReservationLifecycleService {

    ReservationCancelOutcome cancel(Long reservationId, Long userId, LocalDateTime now);

    boolean complete(Long reservationId, LocalDateTime now);
}
