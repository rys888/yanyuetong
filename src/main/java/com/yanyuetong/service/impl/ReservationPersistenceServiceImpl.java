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

/**
 * 预约事实持久化服务，在单个 MySQL 事务内完成名额条件扣减与预约插入。
 * Redis 预占不在该事务边界内，异常情况由请求账本与对账补偿收敛。
 */
@Service
public class ReservationPersistenceServiceImpl implements IReservationPersistenceService {

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private IReservationBatchService reservationBatchService;

    /**
     * 幂等持久化预约。同 ID/用户/批次或同用户已占用该批次时返回 DUPLICATE；
     * 名额条件扣减失败返回 QUOTA_CONFLICT；否则插入事实并返回 PERSISTED。
     * 扣减在插入之前，且二者同事务，插入异常会自动回滚名额。
     *
     * @param reservation 来自 MQ 的预约事实
     * @return 持久化、幂等重复或名额冲突
     */
    @Override
    @Transactional
    public ReservationPersistResult persist(Reservation reservation) {
        // 1、先按消息的预约 ID 查事实。ID、用户、批次都相同才是同一次投递，可直接幂等返回。
        Reservation existing = reservationMapper.selectById(reservation.getId());
        if (existing != null
                && existing.getUserId().equals(reservation.getUserId())
                && existing.getBatchId().equals(reservation.getBatchId())) {
            // 只有三个标识都相同才在此认定为同次投递。同 ID 但归属不同不能在这里静默 ACK，
            // 而要继续后续业务检查；若最终插入触发主键异常，由事务回滚并交给消费者送入死信。
            return ReservationPersistResult.DUPLICATE;
        }
        // 2、再检查用户是否已占用该批次：可能是另一个请求 ID，不应再次扣名额。
        // COMPLETED、NO_SHOW 也占唯一键槽位；CANCELLED 才允许重新预约，与 active_flag 生成列保持一致。
        Integer existingCount = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, reservation.getUserId())
                        .eq(Reservation::getBatchId, reservation.getBatchId())
                        .in(Reservation::getStatus, ReservationStatus.occupyingStatuses()));
        if (existingCount > 0) {
            return ReservationPersistResult.DUPLICATE;
        }

        // 3、用一条带 remaining_quota > 0 条件的 SQL 扣减 MySQL 名额，数据库自身防止并发扣成负数。
        // Redis 是前置预占，不能替代这里的持久化约束；影响 0 行就向消费者报告 QUOTA_CONFLICT。
        // 此处没有重新检查批次是否 PUBLISHED，因此已受理消息不会仅因批次后来关闭就被拒绝。
        boolean quotaDecremented = reservationBatchService.update(
                new LambdaUpdateWrapper<ReservationBatch>()
                        .eq(ReservationBatch::getId, reservation.getBatchId())
                        .gt(ReservationBatch::getRemainingQuota, 0)
                        .setSql("remaining_quota = remaining_quota - 1"));
        if (!quotaDecremented) {
            return ReservationPersistResult.QUOTA_CONFLICT;
        }

        // 4、插入预约事实，与上一步共用 MySQL 事务；插入异常会回滚刚才的扣减。
        // 主键和业务唯一键是最终防线，前面的查询本身不能消除全部并发竞争。
        if (reservationMapper.insert(reservation) != 1) {
            throw new IllegalStateException("预约记录持久化失败");
        }
        // 5、返回后由 Spring 提交事务，消费者随后才推进请求账本并确认 RabbitMQ 消息。
        return ReservationPersistResult.PERSISTED;
    }
}
