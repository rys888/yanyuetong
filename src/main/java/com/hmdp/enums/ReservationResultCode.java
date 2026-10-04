package com.hmdp.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReservationResultCode {
    SUCCESS(0, null),
    BATCH_NOT_OPEN(1, "批次未开放预约"),
    SOLD_OUT(2, "名额已约满"),
    DUPLICATE(3, "你已预约过该批次"),
    BOOKING_NOT_STARTED(4, "预约尚未开始"),
    BOOKING_EXPIRED(5, "预约已截止");

    private final int code;
    private final String message;

    public static ReservationResultCode fromCode(int code) {
        for (ReservationResultCode resultCode : values()) {
            if (resultCode.code == code) {
                return resultCode;
            }
        }
        throw new IllegalArgumentException("未知预约结果码：" + code);
    }
}
