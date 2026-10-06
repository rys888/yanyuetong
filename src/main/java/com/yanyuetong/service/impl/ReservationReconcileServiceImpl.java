package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yanyuetong.dto.ReconcileSummaryDTO;
import com.yanyuetong.entity.Reservation;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.mapper.ReservationMapper;
import com.yanyuetong.service.IReservationReconcileService;
import com.yanyuetong.service.IReservationRequestService;
import com.yanyuetong.utils.RedisConstants;
import com.yanyuetong.utils.ReservationQuotaRollbackExecutor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ReservationReconcileServiceImpl implements IReservationReconcileService {

    private static final String NO_RESERVATION_FAILURE_REASON = "RECONCILE_NO_RESERVATION";
    private static final ReservationRequestStatus[] NON_TERMINAL_STATUSES =
            ReservationRequestStatus.nonTerminalStatuses()
                    .toArray(new ReservationRequestStatus[0]);

    @Resource
    private IReservationRequestService reservationRequestService;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ReservationQuotaRollbackExecutor rollbackExecutor;

    @Resource
    private RedissonClient redissonClient;

    @Value("${yanyuetong.reservation.reconcile-threshold-ms:600000}")
    private long reconcileThresholdMs;

    @Value("${yanyuetong.reservation.reconcile-limit:200}")
    private int reconcileLimit;

    /**
     * 定时调度薄壳：分布式锁仅减少多实例重复工作，正确性仍由状态 CAS 与幂等补偿保证。
     */
    @Scheduled(
            fixedDelayString = "${yanyuetong.reservation.reconcile-interval-ms:300000}",
            initialDelayString = "${yanyuetong.reservation.reconcile-initial-delay-ms:300000}")
    public void scheduledReconcile() {
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_RESERVATION_RECONCILE_KEY);
        boolean locked;
        try {
            locked = lock.tryLock(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("预约对账获取分布式锁时被中断", e);
        }
        if (!locked) {
            log.debug("预约对账未获取到分布式锁，跳过本轮");
            return;
        }

        try {
            ReconcileSummaryDTO summary = reconcile(reconcileLimit);
            if (summary.getScanned() == 0) {
                log.debug("预约对账本轮无陈旧请求");
            } else {
                log.info("预约对账完成：scanned={}，confirmed={}，compensated={}，skipped={}，failed={}",
                        summary.getScanned(), summary.getConfirmed(), summary.getCompensated(),
                        summary.getSkipped(), summary.getFailed());
            }
            int released = releasePendingQuotas(reconcileLimit);
            int noShows = markNoShows(reconcileLimit);
            if (released > 0 || noShows > 0) {
                log.info("预约生命周期补偿完成：releasedQuotas={}，noShows={}", released, noShows);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 对账纯方法。每条请求独立处理；单条异常不会回滚或中断本轮其他请求。
     * 已落库分支使用 casStatus，刻意保留 fail_reason 作为最近一次失败的审计线索。
     */
    @Override
    public ReconcileSummaryDTO reconcile(int limit) {
        LocalDateTime before = LocalDateTime.now()
                .minus(reconcileThresholdMs, ChronoUnit.MILLIS);
        List<ReservationRequest> staleRequests = reservationRequestService.listStale(before, limit);

        int confirmed = 0;
        int compensated = 0;
        int skipped = 0;
        int failed = 0;
        for (ReservationRequest request : staleRequests) {
            try {
                if (reservationMapper.selectById(request.getRequestId()) != null) {
                    boolean updated = reservationRequestService.casStatus(
                            request.getRequestId(),
                            ReservationRequestStatus.CONFIRMED,
                            NON_TERMINAL_STATUSES);
                    if (updated) {
                        confirmed++;
                    } else {
                        skipped++;
                    }
                    continue;
                }

                // 必须先补偿再推进终态：补偿失败时保留非终态，下一轮才能继续重试。
                rollbackExecutor.rollback(request.getBatchId(), request.getUserId());
                boolean updated = reservationRequestService.casStatusWithFailReason(
                        request.getRequestId(),
                        ReservationRequestStatus.COMPENSATED,
                        NO_RESERVATION_FAILURE_REASON,
                        NON_TERMINAL_STATUSES);
                if (updated) {
                    compensated++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                log.error("预约请求对账失败：requestId={}，batchId={}，userId={}",
                        request.getRequestId(), request.getBatchId(), request.getUserId(), e);
            }
        }

        return new ReconcileSummaryDTO(
                staleRequests.size(), confirmed, compensated, skipped, failed);
    }

    /**
     * 重试取消时未成功的 Redis 名额归还。返回本轮完成处理的条数。
     */
    public int releasePendingQuotas(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<Reservation> pending = reservationMapper.selectPendingQuotaReleases(limit);
        int released = 0;
        for (Reservation reservation : pending) {
            try {
                rollbackExecutor.rollback(reservation.getBatchId(), reservation.getUserId());
                int updated = reservationMapper.update(null,
                        new LambdaUpdateWrapper<Reservation>()
                                .eq(Reservation::getId, reservation.getId())
                                .eq(Reservation::getQuotaReleased, false)
                                .set(Reservation::getQuotaReleased, true));
                if (updated == 1) {
                    released++;
                }
            } catch (Exception e) {
                log.error("取消预约 Redis 名额补偿失败：reservationId={}，batchId={}，userId={}",
                        reservation.getId(), reservation.getBatchId(), reservation.getUserId(), e);
            }
        }
        return released;
    }

    /**
     * 批次已结束且仍未核验的预约置为爽约。爽约不归还任何名额。
     */
    public int markNoShows(int limit) {
        if (limit <= 0) {
            return 0;
        }
        return reservationMapper.markNoShows(LocalDateTime.now(), limit);
    }
}
