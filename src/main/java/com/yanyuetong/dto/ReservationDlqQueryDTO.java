package com.yanyuetong.dto;

import com.yanyuetong.enums.ReservationDlqStatus;
import lombok.Data;

@Data
public class ReservationDlqQueryDTO {

    private ReservationDlqStatus status;

    private Integer current = 1;
}
