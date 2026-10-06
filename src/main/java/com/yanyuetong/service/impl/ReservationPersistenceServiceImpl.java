package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.enums.ReservationPersistResult;
import com.yanyuetong.enums.ReservationStatus;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.service.IReservationPersistenceService;
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
        Reservation existing = reservationMapper.selectById(reservation.getId());
        if (existing != null
                && existing.getUserId().equals(reservation.getUserId())
                && existing.getBatchId().equals(reservation.getBatchId())) {
            // 只有「同 id 且同 user 且同 batch」才算同一次预约的重复投递（含 DLQ 重放），
            // 幂等确认、不重复扣名额。同 id 但归属不同是异常的主键冲突，必须继续走到
            // insert 由主键约束抛出，交给消费端既有的 catch(DuplicateKeyException) 进死信队列；
            // 若在此提前返回 DUPLICATE，那条未落库的消息会被静默 ACK，DLQ 上不留任何痕迹。
            return ReservationPersistResult.DUPLICATE;
        }
        Integer existingCount = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, reservation.getUserId())
                        .eq(Reservation::getBatchId, reservation.getBatchId())
                        .in(Reservation::getStatus, ReservationStatus.occupyingStatuses()));
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
