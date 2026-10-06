package com.yanyuetong.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yanyuetong.enums.LaboratoryStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_laboratory")
public class Laboratory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private String campus;

    private String building;

    private String room;

    private String description;

    private LaboratoryStatus status;

    /** 负责该实验室的管理员用户id；null 表示未分配（未分配时任何人都不能管理） */
    private Long adminUserId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
