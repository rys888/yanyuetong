package com.hmdp.dto;

import com.hmdp.enums.EquipmentStatus;
import lombok.Data;

@Data
public class EquipmentQueryDTO {

    private Long laboratoryId;

    private Long categoryId;

    private String campus;

    private String name;

    private EquipmentStatus status;

    private Boolean bookingEnabled;

    private Integer current = 1;
}
