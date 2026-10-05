package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hmdp.enums.ReservationDlqStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预约死信治理台账。
 *
 * <p>同一死信在落库成功、确认前崩溃时可能被重复投递并产生重复行，这是治理日志允许的取舍；
 * 事实来源仍是 tb_reservation，本表不做去重。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_reservation_dlq")
public class ReservationDlq implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long reservationId;

    private String payload;

    private String failureReason;

    private Integer replayCount;

    private ReservationDlqStatus status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
