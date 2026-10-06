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

@Service
public class ReservationRequestServiceImpl implements IReservationRequestService {

    @Resource
    private ReservationRequestMapper reservationRequestMapper;

    @Override
    public void createPreAccepted(long requestId, Long userId, Long batchId) {
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

    @Override
    public boolean casStatus(long requestId, ReservationRequestStatus target,
                             ReservationRequestStatus... expectedStatuses) {
        if (expectedStatuses == null || expectedStatuses.length == 0) {
            throw new IllegalArgumentException("expectedStatuses 不能为空");
        }
        return reservationRequestMapper.update(null, new LambdaUpdateWrapper<ReservationRequest>()
                .eq(ReservationRequest::getRequestId, requestId)
                .in(ReservationRequest::getStatus, Arrays.asList(expectedStatuses))
                .set(ReservationRequest::getStatus, target)) == 1;
    }

    @Override
    public boolean casStatusWithFailReason(long requestId, ReservationRequestStatus target,
                                           String failReason,
                                           ReservationRequestStatus... expectedStatuses) {
        if (expectedStatuses == null || expectedStatuses.length == 0) {
            throw new IllegalArgumentException("expectedStatuses 不能为空");
        }
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

    @Override
    public List<ReservationRequest> listStale(LocalDateTime before, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
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
