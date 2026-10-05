package com.hmdp.dto;

import com.hmdp.enums.ReservationDlqStatus;
import lombok.Data;

@Data
public class ReservationDlqQueryDTO {

    private ReservationDlqStatus status;

    private Integer current = 1;
}
