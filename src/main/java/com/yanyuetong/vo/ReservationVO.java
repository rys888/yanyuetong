package com.yanyuetong.vo;

import com.yanyuetong.enums.ReservationStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ReservationVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    private Long batchId;

    private String equipmentName;

    private String laboratoryName;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime bookingStartTime;

    private LocalDateTime bookingEndTime;

    private ReservationStatus status;

    private Boolean quotaReleased;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
