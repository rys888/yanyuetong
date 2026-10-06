package com.yanyuetong.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yanyuetong.dto.ReservationBatchQueryDTO;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.ReservationBatch;
import com.yanyuetong.enums.BookingState;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.enums.ReservationBatchStatus;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.exception.BadRequestException;
import com.yanyuetong.exception.ForbiddenException;
import com.yanyuetong.mapper.ReservationBatchMapper;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.service.IReservationBatchService;
import com.yanyuetong.utils.SystemConstants;
import com.yanyuetong.utils.UserHolder;
import com.yanyuetong.vo.ReservationBatchVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_KEY;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS;
import static com.yanyuetong.utils.RedisConstants.RESERVATION_BATCH_USERS_KEY;

@Slf4j
@Service
public class ReservationBatchServiceImpl
        extends ServiceImpl<ReservationBatchMapper, ReservationBatch>
        implements IReservationBatchService {

    @Resource
    private IEquipmentService equipmentService;

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Long createBatch(ReservationBatch batch) {
        validateCreateShape(batch);
        assertEquipmentManageable(batch.getEquipmentId());
        ReservationBatch entity = new ReservationBatch()
                .setEquipmentId(batch.getEquipmentId())
                .setStartTime(batch.getStartTime())
                .setEndTime(batch.getEndTime())
                .setBookingStartTime(batch.getBookingStartTime())
                .setBookingEndTime(batch.getBookingEndTime())
                .setTotalQuota(batch.getTotalQuota())
                .setRemainingQuota(batch.getTotalQuota())
                .setStatus(ReservationBatchStatus.DRAFT);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateDraft(ReservationBatch batch) {
        if (batch == null || batch.getId() == null) {
            throw new BadRequestException("批次id不能为空");
        }
        ReservationBatch existing = getRequiredBatch(batch.getId());
        assertEquipmentManageable(existing.getEquipmentId());
        if (existing.getStatus() != ReservationBatchStatus.DRAFT) {
            throw new BadRequestException("仅草稿批次可修改");
        }
        if (batch.getEquipmentId() != null
                && !batch.getEquipmentId().equals(existing.getEquipmentId())) {
            assertEquipmentManageable(batch.getEquipmentId());
        }
        boolean updated = update(null, new LambdaUpdateWrapper<ReservationBatch>()
                .eq(ReservationBatch::getId, batch.getId())
                .eq(ReservationBatch::getStatus, ReservationBatchStatus.DRAFT)
                .set(batch.getEquipmentId() != null,
                        ReservationBatch::getEquipmentId, batch.getEquipmentId())
                .set(batch.getStartTime() != null,
                        ReservationBatch::getStartTime, batch.getStartTime())
                .set(batch.getEndTime() != null,
                        ReservationBatch::getEndTime, batch.getEndTime())
                .set(batch.getBookingStartTime() != null,
                        ReservationBatch::getBookingStartTime, batch.getBookingStartTime())
                .set(batch.getBookingEndTime() != null,
                        ReservationBatch::getBookingEndTime, batch.getBookingEndTime())
                .set(batch.getTotalQuota() != null,
                        ReservationBatch::getTotalQuota, batch.getTotalQuota())
                .set(batch.getTotalQuota() != null,
                        ReservationBatch::getRemainingQuota, batch.getTotalQuota()));
        if (!updated) {
            throw new BadRequestException("批次状态已变化，修改失败");
        }
    }

    @Override
    public void publish(Long id) {
        ReservationBatch batch = getRequiredBatch(id);
        Equipment equipment = assertEquipmentManageable(batch.getEquipmentId());
        if (batch.getStatus() != ReservationBatchStatus.DRAFT) {
            throw new BadRequestException("仅草稿批次可发布");
        }
        LocalDateTime now = LocalDateTime.now();
        validatePublishInvariants(batch, equipment, now);

        String key = quotaKey(id);
        long ttlSeconds = Duration.between(
                now,
                batch.getEndTime().plusHours(RESERVATION_BATCH_QUOTA_TTL_BUFFER_HOURS)).getSeconds();
        if (ttlSeconds <= 0) {
            throw new BadRequestException("批次时间设置不合法");
        }
        Boolean quotaCreated = stringRedisTemplate.opsForValue().setIfAbsent(
                key,
                batch.getTotalQuota().toString(),
                ttlSeconds,
                TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(quotaCreated)) {
            throw new BadRequestException("发布占用标记已存在，疑似重复发布");
        }

        boolean updated = update(null, new LambdaUpdateWrapper<ReservationBatch>()
                .eq(ReservationBatch::getId, id)
                .eq(ReservationBatch::getStatus, ReservationBatchStatus.DRAFT)
                .eq(ReservationBatch::getEquipmentId, batch.getEquipmentId())
                .eq(ReservationBatch::getStartTime, batch.getStartTime())
                .eq(ReservationBatch::getEndTime, batch.getEndTime())
                .eq(ReservationBatch::getBookingStartTime, batch.getBookingStartTime())
                .eq(ReservationBatch::getBookingEndTime, batch.getBookingEndTime())
                .eq(ReservationBatch::getTotalQuota, batch.getTotalQuota())
                .set(ReservationBatch::getStatus, ReservationBatchStatus.PUBLISHED)
                .set(ReservationBatch::getRemainingQuota, batch.getTotalQuota()));
        if (!updated) {
            try {
                stringRedisTemplate.delete(key);
            } catch (RuntimeException ignored) {
                // MySQL remains authoritative; stale quota has a finite TTL.
            }
            throw new BadRequestException("批次状态已被并发修改，发布失败");
        }
    }

    @Override
    public void close(Long id) {
        ReservationBatch batch = getRequiredBatch(id);
        assertEquipmentManageable(batch.getEquipmentId());
        if (batch.getStatus() != ReservationBatchStatus.PUBLISHED) {
            throw new BadRequestException("仅已发布批次可关闭");
        }
        boolean updated = update(null, new LambdaUpdateWrapper<ReservationBatch>()
                .eq(ReservationBatch::getId, id)
                .eq(ReservationBatch::getStatus, ReservationBatchStatus.PUBLISHED)
                .set(ReservationBatch::getStatus, ReservationBatchStatus.CLOSED));
        if (!updated) {
            throw new BadRequestException("批次状态已变化，关闭失败");
        }
        stringRedisTemplate.delete(Arrays.asList(quotaKey(id), usersKey(id)));
    }

    @Override
    public void cancel(Long id) {
        ReservationBatch batch = getRequiredBatch(id);
        assertEquipmentManageable(batch.getEquipmentId());
        if (batch.getStatus() != ReservationBatchStatus.DRAFT
                && batch.getStatus() != ReservationBatchStatus.PUBLISHED) {
            throw new BadRequestException("仅草稿或已发布批次可取消");
        }
        boolean updated = update(null, new LambdaUpdateWrapper<ReservationBatch>()
                .eq(ReservationBatch::getId, id)
                .in(ReservationBatch::getStatus,
                        ReservationBatchStatus.DRAFT,
                        ReservationBatchStatus.PUBLISHED)
                .set(ReservationBatch::getStatus, ReservationBatchStatus.CANCELLED));
        if (!updated) {
            throw new BadRequestException("批次状态已变化，取消失败");
        }
        stringRedisTemplate.delete(Arrays.asList(quotaKey(id), usersKey(id)));
    }

    @Override
    public ReservationBatchVO queryBatchById(Long id) {
        ReservationBatchVO batch = getBaseMapper().selectBatchDetailById(id);
        if (batch == null) {
            return null;
        }
        UserDTO user = UserHolder.getUser();
        if ((user == null || user.getRole() != UserRole.LAB_ADMIN)
                && batch.getStatus() != ReservationBatchStatus.PUBLISHED
                && batch.getStatus() != ReservationBatchStatus.CLOSED) {
            return null;
        }
        fillRemainingQuotaFromRedis(Collections.singletonList(batch));
        fillBookingState(batch, LocalDateTime.now());
        return batch;
    }

    @Override
    public IPage<ReservationBatchVO> queryBatchPage(ReservationBatchQueryDTO query) {
        ReservationBatchQueryDTO actualQuery = query == null ? new ReservationBatchQueryDTO() : query;
        ReservationBatchStatus status = actualQuery.getStatus();
        if (status != null
                && status != ReservationBatchStatus.PUBLISHED
                && status != ReservationBatchStatus.CLOSED) {
            throw new BadRequestException("批次状态不合法");
        }
        return queryPage(actualQuery, null, true);
    }

    @Override
    public IPage<ReservationBatchVO> queryManagePage(ReservationBatchQueryDTO query) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new ForbiddenException("无权查看管理端批次");
        }
        ReservationBatchQueryDTO actualQuery = query == null ? new ReservationBatchQueryDTO() : query;
        return queryPage(actualQuery, user.getId(), false);
    }

    private IPage<ReservationBatchVO> queryPage(
            ReservationBatchQueryDTO query,
            Long adminUserId,
            boolean publicOnly) {
        Integer current = query.getCurrent() == null ? 1 : query.getCurrent();
        Page<ReservationBatchVO> page = new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE);
        IPage<ReservationBatchVO> result = getBaseMapper()
                .selectReservationBatchPage(page, query, adminUserId, publicOnly);
        fillRemainingQuotaFromRedis(result.getRecords());
        LocalDateTime now = LocalDateTime.now();
        result.getRecords().forEach(batch -> fillBookingState(batch, now));
        return result;
    }

    private void validateCreateShape(ReservationBatch batch) {
        if (batch == null
                || batch.getEquipmentId() == null
                || batch.getStartTime() == null
                || batch.getEndTime() == null
                || batch.getBookingStartTime() == null
                || batch.getBookingEndTime() == null
                || batch.getTotalQuota() == null) {
            throw new BadRequestException("批次必填字段不能为空");
        }
    }

    private ReservationBatch getRequiredBatch(Long id) {
        ReservationBatch batch = id == null ? null : getById(id);
        if (batch == null) {
            throw new ForbiddenException("无权管理该批次");
        }
        return batch;
    }

    private Equipment assertEquipmentManageable(Long equipmentId) {
        if (equipmentId == null) {
            throw new BadRequestException("设备id不能为空");
        }
        Equipment equipment = equipmentService.getById(equipmentId);
        if (equipment == null) {
            throw new ForbiddenException("无权管理该批次");
        }
        laboratoryService.assertManageable(equipment.getLaboratoryId());
        return equipment;
    }

    private void validatePublishInvariants(
            ReservationBatch batch,
            Equipment equipment,
            LocalDateTime now) {
        if (batch.getTotalQuota() == null || batch.getTotalQuota() < 1) {
            throw new BadRequestException("总名额必须大于等于1");
        }
        if (batch.getStartTime() == null
                || batch.getEndTime() == null
                || !batch.getStartTime().isBefore(batch.getEndTime())) {
            throw new BadRequestException("使用开始时间必须早于结束时间");
        }
        if (batch.getBookingStartTime() == null
                || batch.getBookingEndTime() == null
                || !batch.getBookingStartTime().isBefore(batch.getBookingEndTime())) {
            throw new BadRequestException("预约开放时间必须早于截止时间");
        }
        if (batch.getBookingEndTime().isAfter(batch.getStartTime())) {
            throw new BadRequestException("预约截止时间不能晚于使用开始时间");
        }
        if (!batch.getBookingEndTime().isAfter(now)) {
            throw new BadRequestException("预约截止时间必须晚于当前时间");
        }
        if (equipment.getStatus() != EquipmentStatus.AVAILABLE
                || !Boolean.TRUE.equals(equipment.getBookingEnabled())) {
            throw new BadRequestException("设备当前不可预约");
        }
        long overlapCount = count(new LambdaQueryWrapper<ReservationBatch>()
                .ne(ReservationBatch::getId, batch.getId())
                .ne(ReservationBatch::getStatus, ReservationBatchStatus.CANCELLED)
                .eq(ReservationBatch::getEquipmentId, batch.getEquipmentId())
                .lt(ReservationBatch::getStartTime, batch.getEndTime())
                .gt(ReservationBatch::getEndTime, batch.getStartTime()));
        if (overlapCount > 0) {
            throw new BadRequestException("同一设备的使用时段不能重叠");
        }
    }

    private void fillBookingState(ReservationBatchVO batch, LocalDateTime now) {
        batch.setBookingState(BookingState.of(
                batch.getStatus(),
                batch.getBookingStartTime(),
                batch.getBookingEndTime(),
                now));
    }

    private void fillRemainingQuotaFromRedis(List<ReservationBatchVO> batches) {
        List<ReservationBatchVO> published = batches.stream()
                .filter(batch -> batch.getStatus() == ReservationBatchStatus.PUBLISHED)
                .collect(Collectors.toList());
        if (published.isEmpty()) {
            return;
        }
        List<String> keys = published.stream()
                .map(batch -> quotaKey(batch.getId()))
                .collect(Collectors.toList());
        try {
            List<String> quotas = stringRedisTemplate.opsForValue().multiGet(keys);
            if (quotas == null) {
                log.warn("批次名额批量读取返回空，回落 MySQL：batchIds={}",
                        published.stream().map(ReservationBatchVO::getId).collect(Collectors.toList()));
                return;
            }
            List<Long> missingBatchIds = new ArrayList<>();
            for (int i = 0; i < published.size(); i++) {
                String quota = quotas.get(i);
                if (quota != null) {
                    published.get(i).setRemainingQuota(Integer.valueOf(quota));
                } else {
                    missingBatchIds.add(published.get(i).getId());
                }
            }
            if (!missingBatchIds.isEmpty()) {
                log.warn("批次名额键不存在，回落 MySQL：batchIds={}", missingBatchIds);
            }
        } catch (RuntimeException e) {
            log.warn("批次名额读取 Redis 失败，回落 MySQL：keys={}", keys, e);
        }
    }

    private String quotaKey(Long id) {
        return RESERVATION_BATCH_QUOTA_KEY + id;
    }

    private String usersKey(Long id) {
        return RESERVATION_BATCH_USERS_KEY + id;
    }
}
