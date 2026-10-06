package com.yanyuetong.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yanyuetong.enums.ReservationBatchStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reservation_batch")
public class ReservationBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long equipmentId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime bookingStartTime;

    private LocalDateTime bookingEndTime;

    private Integer totalQuota;

    private Integer remainingQuota;

    private ReservationBatchStatus status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
