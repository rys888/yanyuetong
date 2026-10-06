package com.yanyuetong.service;

import com.yanyuetong.entity.Reservation;
import com.yanyuetong.enums.ReservationPersistResult;

public interface IReservationPersistenceService {
    ReservationPersistResult persist(Reservation reservation);
}
