package com.yanyuetong.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yanyuetong.enums.ReservationRequestStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reservation_request")
public class ReservationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "request_id", type = IdType.INPUT)
    private Long requestId;

    private Long userId;

    private Long batchId;

    private ReservationRequestStatus status;

    private String failReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
