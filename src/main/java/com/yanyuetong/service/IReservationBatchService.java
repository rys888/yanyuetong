package com.yanyuetong.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.yanyuetong.dto.ReservationBatchQueryDTO;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.vo.ReservationBatchVO;

public interface IReservationBatchService extends IService<ReservationBatch> {

    Long createBatch(ReservationBatch batch);

    void updateDraft(ReservationBatch batch);

    void publish(Long id);

    void close(Long id);

    void cancel(Long id);

    ReservationBatchVO queryBatchById(Long id);

    IPage<ReservationBatchVO> queryBatchPage(ReservationBatchQueryDTO query);

    IPage<ReservationBatchVO> queryManagePage(ReservationBatchQueryDTO query);
}
