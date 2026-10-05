package com.hmdp.service.impl;

import com.hmdp.dto.ReconcileSummaryDTO;
import com.hmdp.entity.ReservationRequest;
import com.hmdp.enums.ReservationRequestStatus;
import com.hmdp.mapper.ReservationMapper;
import com.hmdp.service.IReservationReconcileService;
import com.hmdp.service.IReservationRequestService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.ReservationQuotaRollbackExecutor;
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

    @Value("${hmdp.reservation.reconcile-threshold-ms:600000}")
    private long reconcileThresholdMs;

    @Value("${hmdp.reservation.reconcile-limit:200}")
    private int reconcileLimit;

    /**
     * 定时调度薄壳：分布式锁仅减少多实例重复工作，正确性仍由状态 CAS 与幂等补偿保证。
     */
    @Scheduled(fixedDelayString = "${hmdp.reservation.reconcile-interval-ms:300000}")
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
                return;
            }
            log.info("预约对账完成：scanned={}，confirmed={}，compensated={}，skipped={}，failed={}",
                    summary.getScanned(), summary.getConfirmed(), summary.getCompensated(),
                    summary.getSkipped(), summary.getFailed());
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
}
