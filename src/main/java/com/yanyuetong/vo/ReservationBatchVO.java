package com.yanyuetong.vo;

import com.yanyuetong.enums.BookingState;
import com.yanyuetong.enums.ReservationBatchStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ReservationBatchVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long equipmentId;

    private String equipmentName;

    private Long laboratoryId;

    private String laboratoryName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime bookingStartTime;

    private LocalDateTime bookingEndTime;

    private Integer totalQuota;

    private Integer remainingQuota;

    private ReservationBatchStatus status;

    private BookingState bookingState;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
