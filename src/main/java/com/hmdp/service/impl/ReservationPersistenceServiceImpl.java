package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.entity.Reservation;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.enums.ReservationPersistResult;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationPersistenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Service
public class ReservationPersistenceServiceImpl implements IReservationPersistenceService {

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private IReservationBatchService reservationBatchService;

    @Override
    @Transactional
    public ReservationPersistResult persist(Reservation reservation) {
        Integer existingCount = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, reservation.getUserId())
                        .eq(Reservation::getBatchId, reservation.getBatchId()));
        if (existingCount > 0) {
            return ReservationPersistResult.DUPLICATE;
        }

        // 必须先扣减名额；影响 0 行时直接返回，库中不会留下未扣名额的预约。
        boolean quotaDecremented = reservationBatchService.update(
                new LambdaUpdateWrapper<ReservationBatch>()
                        .eq(ReservationBatch::getId, reservation.getBatchId())
                        .gt(ReservationBatch::getRemainingQuota, 0)
                        .setSql("remaining_quota = remaining_quota - 1"));
        if (!quotaDecremented) {
            return ReservationPersistResult.QUOTA_CONFLICT;
        }

        if (reservationMapper.insert(reservation) != 1) {
            throw new IllegalStateException("预约记录持久化失败");
        }
        return ReservationPersistResult.PERSISTED;
    }
}
