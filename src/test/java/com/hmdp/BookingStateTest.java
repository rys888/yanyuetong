package com.hmdp;

import com.hmdp.enums.BookingState;
import com.hmdp.enums.ReservationBatchStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingStateTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 10, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 10, 17, 0);

    @Test
    void shouldReturnDraftForDraftBatch() {
        assertEquals(BookingState.DRAFT,
                BookingState.of(ReservationBatchStatus.DRAFT, START, END, START.plusHours(1)));
    }

    @Test
    void shouldReturnNotStartedBeforeBookingWindow() {
        assertEquals(BookingState.NOT_STARTED,
                BookingState.of(ReservationBatchStatus.PUBLISHED, START, END, START.minusSeconds(1)));
    }

    @Test
    void shouldReturnOpenInsideBookingWindow() {
        assertEquals(BookingState.OPEN,
                BookingState.of(ReservationBatchStatus.PUBLISHED, START, END, START.plusHours(1)));
    }

    @Test
    void shouldReturnExpiredAfterBookingWindow() {
        assertEquals(BookingState.EXPIRED,
                BookingState.of(ReservationBatchStatus.PUBLISHED, START, END, END.plusSeconds(1)));
    }

    @Test
    void shouldReturnClosedBeforeCheckingTimeWindow() {
        assertEquals(BookingState.CLOSED,
                BookingState.of(ReservationBatchStatus.CLOSED, START, END, START.plusHours(1)));
    }

    @Test
    void shouldReturnCancelledBeforeCheckingTimeWindow() {
        assertEquals(BookingState.CANCELLED,
                BookingState.of(ReservationBatchStatus.CANCELLED, START, END, START.plusHours(1)));
    }

    @Test
    void shouldTreatBookingStartAsOpen() {
        assertEquals(BookingState.OPEN,
                BookingState.of(ReservationBatchStatus.PUBLISHED, START, END, START));
    }

    @Test
    void shouldTreatBookingEndAsOpen() {
        assertEquals(BookingState.OPEN,
                BookingState.of(ReservationBatchStatus.PUBLISHED, START, END, END));
    }

    @Test
    void shouldExpireOneSecondAfterBookingEnd() {
        assertEquals(BookingState.EXPIRED,
                BookingState.of(ReservationBatchStatus.PUBLISHED, START, END, END.plusSeconds(1)));
    }
}
