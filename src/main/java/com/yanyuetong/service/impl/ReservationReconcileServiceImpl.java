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

/**
 * 预约最终一致性与生命周期补偿任务。它收敛超时非终态请求、失败的 Redis 名额归还和过期未履约预约。
 * Redisson 锁减少定时任务多实例重复扫描，CAS 与 Lua 限制重复修改；它们不构成跨存储事务。
 */
@Slf4j
@Service
public class ReservationReconcileServiceImpl implements IReservationReconcileService {

    private static final String NO_RESERVATION_FAILURE_REASON = "RECONCILE_NO_RESERVATION";
    private static final String RECONCILE_ATTEMPT_FAILED_REASON = "RECONCILE_ATTEMPT_FAILED";
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
     * 定时调度入口：获得任务级锁后依次执行请求对账、取消名额补偿和爽约处理。
     */
    @Scheduled(
            fixedDelayString = "${yanyuetong.reservation.reconcile-interval-ms:300000}",
            initialDelayString = "${yanyuetong.reservation.reconcile-initial-delay-ms:300000}")
    public void scheduledReconcile() {
        // 1、所有实例争用同一个任务锁，未获锁就跳过；未指定租期，Redisson 可用看门狗续期。
        // 这是对账锁，不是消费者按 userId 获取的锁，因此不会阻塞正常消费。
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
            // 2、先收敛超时请求，再处理取消后未归还的 Redis 名额，最后标记已结束但未核验的预约。
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
            // 3、即使本轮处理异常，也释放当前线程仍持有的任务锁，允许后续调度继续。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 对账超过阈值仍处于非终态的请求。事实表有记录则转 CONFIRMED；
     * 无记录则先幂等归还 Redis 名额，再转 COMPENSATED。
     * 每条请求独立处理；单条异常不会回滚或中断本轮其他请求。
     * 已落库分支使用 casStatus，刻意保留 fail_reason 作为最近一次失败的审计线索。
     *
     * @param limit 本轮最多处理条数
     * @return 扫描、确认、补偿、冲突跳过与失败统计
     */
    @Override
    public ReconcileSummaryDTO reconcile(int limit) {
        // 1、按更新时间阈值取得有限候选，避免把仍在正常传递和消费的请求立即视为失败。
        LocalDateTime before = LocalDateTime.now()
                .minus(reconcileThresholdMs, ChronoUnit.MILLIS);
        List<ReservationRequest> staleRequests = reservationRequestService.listStale(before, limit);

        int confirmed = 0;
        int compensated = 0;
        int skipped = 0;
        int failed = 0;
        for (ReservationRequest request : staleRequests) {
            try {
                // 2、逐条以 MySQL 预约事实为准：已有记录就尝试确认账本，覆盖“落库成功、账本更新失败”。
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

                // 3、未查到事实时先调用幂等 Lua 归还占位，再 CAS 为 COMPENSATED；回滚异常则保留非终态。
                // 注意：查事实、回滚 Redis、更新账本并非原子操作，也未持有消费者的用户锁；
                // 消费者可能在查询之后落库，因此“此时查不到”不等于“此后绝不会落库”。
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
                // 4、单条异常独立记录，不中断其他候选；推进更新时间，让故障请求稍后再进入扫描窗口。
                failed++;
                log.error("预约请求对账失败：requestId={}，batchId={}，userId={}",
                        request.getRequestId(), request.getBatchId(), request.getUserId(), e);
                recordReconcileFailureSafely(request);
            }
        }

        return new ReconcileSummaryDTO(
                staleRequests.size(), confirmed, compensated, skipped, failed);
    }

    /**
     * 重试取消时未成功的 Redis 名额归还。先执行幂等 Lua，再以 quota_released=false 条件更新标记，
     * 在用户占位未被重新创建的前提下避免重复归还。返回本轮成功更新释放标记的条数。
     */
    public int releasePendingQuotas(int limit) {
        // 1、扫描 CANCELLED 且 quota_released=false 的事实，接续用户取消后的 Redis 补偿。
        if (limit <= 0) {
            return 0;
        }
        List<Reservation> pending = reservationMapper.selectPendingQuotaReleases(limit);
        int released = 0;
        for (Reservation reservation : pending) {
            try {
                // 2、先归还占位，再按未释放条件更新标记。两个操作跨存储，后者失败时下轮仍可能重试。
                // Lua 以用户占位去重而非预约 ID 去重；若该用户已重新预约，旧补偿也可能碰到新占位。
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
                // 3、失败时保留待补偿标记，并把更新时间移到队尾，避免少量故障记录长期占满 LIMIT。
                log.error("取消预约 Redis 名额补偿失败：reservationId={}，batchId={}，userId={}",
                        reservation.getId(), reservation.getBatchId(), reservation.getUserId(), e);
                deferPendingQuotaReleaseSafely(reservation);
            }
        }
        return released;
    }

    private void recordReconcileFailureSafely(ReservationRequest request) {
        try {
            reservationRequestService.recordFailReason(
                    request.getRequestId(), RECONCILE_ATTEMPT_FAILED_REASON);
        } catch (Exception e) {
            log.error("预约请求对账失败原因记录失败：requestId={}", request.getRequestId(), e);
        }
    }

    private void deferPendingQuotaReleaseSafely(Reservation reservation) {
        try {
            // 失败记录移到队尾，避免小批量 LIMIT 扫描被同一条故障记录持续占满。
            reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                    .eq(Reservation::getId, reservation.getId())
                    .set(Reservation::getUpdateTime, LocalDateTime.now()));
        } catch (Exception e) {
            log.error("取消预约 Redis 名额补偿重试时间推进失败：reservationId={}",
                    reservation.getId(), e);
        }
    }

    /**
     * 批次已结束且仍未核验的预约置为爽约。爽约不归还任何名额。
     */
    public int markNoShows(int limit) {
        if (limit <= 0) {
            return 0;
        }
        // SQL 只将使用已结束、批次未取消且仍为 RESERVED 的记录转为 NO_SHOW；不修改名额或释放标记。
        // 扫描范围是全库满足条件的记录，不是本轮 reconcile 扫到的请求集合。
        return reservationMapper.markNoShows(LocalDateTime.now(), limit);
    }
}
