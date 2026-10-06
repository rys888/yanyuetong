package com.yanyuetong.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.ReservationQueryDTO;
import com.yanyuetong.vo.ReservationVO;

public interface IReservationService {
    Long submit(Long batchId);

    void cancel(Long reservationId);

    void verify(Long reservationId);

    IPage<ReservationVO> queryMyPage(ReservationQueryDTO query);

    ReservationVO queryMine(Long reservationId);

    IPage<ReservationVO> queryManagePage(ReservationQueryDTO query);
}
