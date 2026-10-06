package com.yanyuetong;

import com.yanyuetong.enums.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationStatusTest {

    @Test
    void shouldExposeOnlyReservedAsActiveAndAllOtherStatesAsTerminal() {
        assertTrue(ReservationStatus.RESERVED.isActive());
        assertFalse(ReservationStatus.RESERVED.isTerminal());

        assertFalse(ReservationStatus.CANCELLED.isActive());
        assertTrue(ReservationStatus.CANCELLED.isTerminal());
        assertTrue(ReservationStatus.COMPLETED.isTerminal());
        assertTrue(ReservationStatus.NO_SHOW.isTerminal());
    }

    /**
     * 占用判据必须与 tb_reservation.active_flag 生成列
     * CASE WHEN status = 'CANCELLED' THEN NULL ELSE 1 END 严格一致：
     * 只有 CANCELLED 释放槽位，COMPLETED / NO_SHOW 仍然占用。
     */
    @Test
    void shouldTreatEveryStatusExceptCancelledAsOccupyingTheUniqueSlot() {
        assertEquals(Arrays.asList(ReservationStatus.RESERVED, ReservationStatus.COMPLETED,
                        ReservationStatus.NO_SHOW),
                ReservationStatus.occupyingStatuses());
        assertFalse(ReservationStatus.occupyingStatuses().contains(ReservationStatus.CANCELLED));
        assertThrows(UnsupportedOperationException.class,
                () -> ReservationStatus.occupyingStatuses().add(ReservationStatus.CANCELLED));
    }
}
