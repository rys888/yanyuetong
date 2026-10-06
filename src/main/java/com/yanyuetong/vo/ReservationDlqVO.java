package com.yanyuetong.vo;

import com.yanyuetong.enums.ReservationDlqStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 死信治理视图。分页列表为避免放大响应体不查询 payload，因此列表中的 payload 固定为 null；
 * 单条详情会返回完整 payload。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ReservationDlqVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long reservationId;

    private String payload;

    private String failureReason;

    private Integer replayCount;

    private ReservationDlqStatus status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
