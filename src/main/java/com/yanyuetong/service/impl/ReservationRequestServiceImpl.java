package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yanyuetong.entity.ReservationRequest;
import com.yanyuetong.enums.ReservationRequestStatus;
import com.yanyuetong.mapper.ReservationRequestMapper;
import com.yanyuetong.service.IReservationRequestService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 预约请求账本服务。账本记录跨 Redis、RabbitMQ 和 MySQL 的处理进度，
 * 为生产者回写、消费者确认与定时对账提供共同的最终一致性依据。
 */
@Service
public class ReservationRequestServiceImpl implements IReservationRequestService {

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Override
    public void createPreAccepted(long requestId, Long userId, Long batchId) {
        // 先建账只是登记一次尝试，不代表已抢到名额；提交入口随后才执行 Redis Lua。
        ReservationRequest request = new ReservationRequest()
                .setRequestId(requestId)
                .setUserId(userId)
                .setBatchId(batchId)
                .setStatus(ReservationRequestStatus.PRE_ACCEPTED);
        reservationRequestMapper.insert(request);
    }

    @Override
    public ReservationRequest getByRequestId(long requestId) {
        return reservationRequestMapper.selectById(requestId);
    }

    /**
     * 按预期状态集合原子推进账本。影响行数为 0 表示记录不存在或已被其他链路抢先推进，
     * 调用方应按 CAS 冲突处理，不能无条件覆盖。
     *
     * @param requestId 请求 ID
     * @param target 目标状态
     * @param expectedStatuses 允许转出的当前状态集合
     * @return 是否有且仅有一行成功转换
     */
    @Override
    public boolean casStatus(long requestId, ReservationRequestStatus target,
                             ReservationRequestStatus... expectedStatuses) {
        // 1、调用方必须声明允许转出的状态；此方法不内置完整转换规则，不能只给目标状态无条件覆盖。
        if (expectedStatuses == null || expectedStatuses.length == 0) {
            throw new IllegalArgumentException("expectedStatuses 不能为空");
        }
        // 2、把“当前状态符合预期”和“修改状态”放在同一条 UPDATE 中，形成数据库 CAS。
        // true 表示这一行成功推进；false 还可能是记录不存在，需要调用方结合实际场景判断。
        return reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId)
                .in(ReservationRequest::getStatus, Arrays.asList(expectedStatuses))
                .set(ReservationRequest::getStatus, target)) == 1;
    }

    /**
     * 与状态一起原子写入失败原因，避免终态已变更但审计原因缺失。
     */
    @Override
    public boolean casStatusWithFailReason(long requestId, ReservationRequestStatus target,
                                           String failReason,
                                           ReservationRequestStatus... expectedStatuses) {
        // 1、同样要求给出预期状态，避免补偿线程覆盖消费者已经确认的终态。
        if (expectedStatuses == null || expectedStatuses.length == 0) {
            throw new IllegalArgumentException("expectedStatuses 不能为空");
        }
        // 2、状态与原因同一条 SQL 写入；原子性仅限这行账本，不包含调用方先前的 Redis 回滚。
        return reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId)
                .in(ReservationRequest::getStatus, Arrays.asList(expectedStatuses))
                .set(ReservationRequest::getStatus, target)
                .set(ReservationRequest::getFailReason, failReason)) == 1;
    }

    @Override
    public boolean recordFailReason(long requestId, String failReason) {
        // 首次写入失败原因会触发 MySQL 的 ON UPDATE，但重复写入同一原因是 no-op，
        // 不会自动推进 update_time；这里必须显式赋值，避免持续失败行重新钉在扫描窗口队首。
        return reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId)
                .set(ReservationRequest::getFailReason, failReason)
                .set(ReservationRequest::getUpdateTime, LocalDateTime.now())) == 1;
    }

    /**
     * 按最后更新时间扫描超时的非终态请求，供对账任务有界分批处理。
     * 稳定的 update_time/request_id 排序可避免持续遗漏同一时间窗内的记录。
     */
    @Override
    public List<ReservationRequest> listStale(LocalDateTime before, int limit) {
        // 1、无有效批量上限就不扫描，防止一次调度无界处理。
        if (limit <= 0) {
            return Collections.emptyList();
        }
        // 2、只选 PRE_ACCEPTED/PUBLISHED/UNKNOWN，且最后更新早于阈值的记录，为正常异步消费留出时间。
        // 按更新时间和 ID 排序后 LIMIT；这里只取候选快照，处理时仍需 CAS 应对消费者并发更新。
        List<ReservationRequest> requests = reservationRequestMapper.selectList(
                new LambdaQueryWrapper<ReservationRequest>()
                        .in(ReservationRequest::getStatus,
                                ReservationRequestStatus.nonTerminalStatuses())
                        .lt(ReservationRequest::getUpdateTime, before)
                        .orderByAsc(ReservationRequest::getUpdateTime)
                        .orderByAsc(ReservationRequest::getRequestId)
                        .last("LIMIT " + limit));
        return requests == null ? Collections.emptyList() : requests;
    }
}
