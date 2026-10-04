package com.hmdp.dto;

import com.hmdp.enums.ReservationBatchStatus;
import lombok.Data;

@Data
public class ReservationBatchQueryDTO {

    private Long equipmentId;

    private Long laboratoryId;

    private ReservationBatchStatus status;

    private Integer current = 1;
}
