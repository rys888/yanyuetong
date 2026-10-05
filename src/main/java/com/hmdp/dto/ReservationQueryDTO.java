package com.hmdp.dto;

import com.hmdp.enums.ReservationStatus;
import lombok.Data;

@Data
public class ReservationQueryDTO {

    private Long batchId;

    private ReservationStatus status;

    private Integer current = 1;
}
