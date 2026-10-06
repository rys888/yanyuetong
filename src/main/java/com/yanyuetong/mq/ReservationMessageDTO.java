package com.yanyuetong.mq;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class ReservationMessageDTO implements Serializable {

    private Long reservationId;

    private Long userId;

    private Long batchId;

    private LocalDateTime createTime;
}
