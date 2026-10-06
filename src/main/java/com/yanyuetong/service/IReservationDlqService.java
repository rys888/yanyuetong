package com.yanyuetong.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.ReservationDlqQueryDTO;
import com.yanyuetong.entity.ReservationDlq;
import com.yanyuetong.vo.ReservationDlqReplayVO;
import com.yanyuetong.vo.ReservationDlqVO;

public interface IReservationDlqService {

    /** 从消息原文提取 reservationId；任何无法解析的输入均返回 null。 */
    Long extractReservationId(String payload);

    /** 记录一条死信治理台账，返回完成落库的实体。 */
    ReservationDlq recordDeadLetter(byte[] body, String xDeathReason);

    /** 分页查询治理台账；列表不加载 payload。 */
    IPage<ReservationDlqVO> queryPage(ReservationDlqQueryDTO query);

    /** 查询单条治理台账详情，包含完整 payload。 */
    ReservationDlqVO queryById(Long id);

    /** 按治理规则尝试人工重放一条死信。 */
    ReservationDlqReplayVO replay(Long id);
}
