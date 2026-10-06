package com.yanyuetong.dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ReservationSubmitDTO {
    private Long batchId;
}
