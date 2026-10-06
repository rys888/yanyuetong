package com.yanyuetong.vo;

import com.yanyuetong.enums.LaboratoryStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class LaboratoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private String campus;

    private String building;

    private String room;

    private String description;

    private LaboratoryStatus status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
