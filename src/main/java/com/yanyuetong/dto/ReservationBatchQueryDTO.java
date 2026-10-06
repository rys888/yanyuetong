package com.yanyuetong.dto;

import com.yanyuetong.enums.ReservationBatchStatus;
import lombok.Data;

@Data
public class ReservationBatchQueryDTO {

    private Long equipmentId;

    private Long laboratoryId;

    private ReservationBatchStatus status;

    private Integer current = 1;
}
