package com.yanyuetong.dto;

import com.yanyuetong.enums.ReservationStatus;
import lombok.Data;

@Data
public class ReservationQueryDTO {

    private Long batchId;

    private ReservationStatus status;

    private Integer current = 1;
}
