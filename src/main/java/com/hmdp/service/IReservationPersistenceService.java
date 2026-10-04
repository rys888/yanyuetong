package com.hmdp.service;

import com.hmdp.entity.Reservation;
import com.hmdp.enums.ReservationPersistResult;

public interface IReservationPersistenceService {
    ReservationPersistResult persist(Reservation reservation);
}
