package com.hmdp.vo;

import com.hmdp.enums.EquipmentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class EquipmentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long laboratoryId;

    private Long categoryId;

    private String name;

    private String description;

    private String location;

    private String image;

    private String usageRequirement;

    private EquipmentStatus status;

    private Boolean bookingEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String laboratoryName;

    private String campus;

    private String building;

    private String room;

    private String categoryName;
}
