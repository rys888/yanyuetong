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

/**
 * 批次管理与名额初始化：发布时连接 MySQL 批次状态和 Redis 预占入口，关闭或取消后清理入口。
 * 批次状态决定是否受理新请求，已受理消息的落库则由预约消费链路继续处理。
 */
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
        // 1、校验实验室归属、草稿状态、设备可用性和两个时间窗口，确保发布的是有效批次。
        ReservationBatch batch = getRequiredBatch(id);
        Equipment equipment = assertEquipmentManageable(batch.getEquipmentId());
        if (batch.getStatus() != ReservationBatchStatus.DRAFT) {
            throw new BadRequestException("仅草稿批次可发布");
        }
        LocalDateTime now = LocalDateTime.now();
        validatePublishInvariants(batch, equipment, now);

        // 2、先在 Redis 创建名额键，TTL 延续至使用结束后的缓冲期，供预约 Lua 与延迟补偿使用。
        // SET NX 把初始化和过期时间一起写入，阻止并发发布重置已经创建的名额。
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

        // 3、以读取时的状态和业务字段作为 CAS 条件，防止管理员并发修改草稿后仍发布旧快照。
        // 先初始化 Redis，再开放 MySQL 状态，减少用户看到 PUBLISHED 但名额尚未初始化的窗口。
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
            // 4、CAS 未命中时撤销名额键；Redis 与 MySQL 没有共同事务，此分支是显式清理。
            try {
                stringRedisTemplate.delete(key);
            } catch (RuntimeException ignored) {
                // 删除失败时保留有限 TTL；MySQL 未发布仍会被提交入口拒绝，不能仅凭 Redis 键受理预约。
            }
            throw new BadRequestException("批次状态已被并发修改，发布失败");
        }
    }

    @Override
    public void close(Long id) {
        // 1、确认管理归属并通过 CAS 将 PUBLISHED→CLOSED，让后续提交在 MySQL 状态检查时被拒绝。
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
        // 2、删除名额和用户集合，关闭 Redis 预占入口；这不会撤销关闭前已发送的创建消息。
        stringRedisTemplate.delete(Arrays.asList(quotaKey(id), usersKey(id)));
    }

    @Override
    public void cancel(Long id) {
        // 1、批次取消只允许 DRAFT/PUBLISHED→CANCELLED；它与“用户取消单条预约”是不同用例。
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
        // 2、状态更新成功后清理 Redis 入口；此方法没有逐条取消已经存在的预约事实。
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
        // 1、区分“预约开放/截止”和“设备使用开始/结束”两个窗口，预约截止不能晚于使用开始。
        // 这些约束也是 Redis 名额与用户占位 TTL 计算的前提，不能只检查剩余名额。
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
        // 2、只有可用且允许预约的设备才能发布；提交预约时还会再次读取设备状态进行检查。
        if (equipment.getStatus() != EquipmentStatus.AVAILABLE
                || !Boolean.TRUE.equals(equipment.getBookingEnabled())) {
            throw new BadRequestException("设备当前不可预约");
        }
        // 3、排除自身和已取消批次，检查同设备使用时段交叉；两个时段首尾相接不算重叠。
        // 这里只是查询校验，没有给设备范围加锁，不能把它当成并发发布下的唯一性约束。
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
        // 1、只覆盖已发布批次的展示名额：Redis 包含已预占但尚未落库的数量，比 MySQL 更接近受理现场。
        List<ReservationBatchVO> published = batches.stream()
                .filter(batch -> batch.getStatus() == ReservationBatchStatus.PUBLISHED)
                .collect(Collectors.toList());
        if (published.isEmpty()) {
            return;
        }
        // 2、按同一顺序批量读取 Key，避免列表中每个批次分别访问 Redis。
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
            // 3、有值就覆盖展示字段；缺失或读取异常保留原来的 MySQL 值，仅用于查询展示。
            // 这里不补建 Redis 名额键，提交时仍需 Lua 真正预占，不能把展示回退当作可以预约。
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
