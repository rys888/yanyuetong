package com.hmdp.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.ReservationBatchQueryDTO;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.vo.ReservationBatchVO;

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
