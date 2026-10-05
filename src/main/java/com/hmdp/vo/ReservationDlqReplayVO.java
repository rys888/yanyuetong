package com.hmdp.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ReservationDlqReplayVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean replayed;

    private String reason;

    private ReservationDlqVO record;
}
