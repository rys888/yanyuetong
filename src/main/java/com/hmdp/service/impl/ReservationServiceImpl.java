package com.hmdp.service.impl;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.ReservationBatch;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.enums.ReservationBatchStatus;
import com.hmdp.enums.ReservationResultCode;
import com.hmdp.exception.BadRequestException;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.IReservationBatchService;
import com.hmdp.service.IReservationService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.ReservationQuotaExecutor;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class ReservationServiceImpl implements IReservationService {

    @Resource
    private IReservationBatchService reservationBatchService;

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private ReservationQuotaExecutor reservationQuotaExecutor;

    @Override
    public Long submit(Long batchId) {
        if (batchId == null) {
            throw new BadRequestException("预约批次不存在");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException("请先登录");
        }
        ReservationBatch batch = reservationBatchService.getById(batchId);
        if (batch == null) {
            throw new BadRequestException("预约批次不存在");
        }
        if (batch.getStatus() != ReservationBatchStatus.PUBLISHED) {
            throw new BadRequestException("批次当前不可预约");
        }
        Equipment equipment = equipmentService.getById(batch.getEquipmentId());
        if (equipment == null
                || equipment.getStatus() != EquipmentStatus.AVAILABLE
                || !Boolean.TRUE.equals(equipment.getBookingEnabled())) {
            throw new BadRequestException("设备当前不可预约");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(batch.getBookingStartTime())) {
            throw new BadRequestException("预约尚未开始");
        }
        if (now.isAfter(batch.getBookingEndTime())) {
            throw new BadRequestException("预约已截止");
        }
        long reservationId = redisIdWorker.nextId("reservation");
        ReservationResultCode resultCode = reservationQuotaExecutor.tryAcquire(batch, user.getId(), now);
        if (resultCode != ReservationResultCode.SUCCESS) {
            log.warn("预约名额抢占被拒：batchId={}, userId={}, resultCode={}",
                    batchId, user.getId(), resultCode);
            throw new BadRequestException(resultCode.getMessage());
        }
        return reservationId;
    }
}
