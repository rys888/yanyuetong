package com.yanyuetong.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yanyuetong.dto.EquipmentQueryDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.entity.EquipmentCategory;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.enums.EquipmentStatus;
import com.yanyuetong.exception.ForbiddenException;
import com.yanyuetong.mapper.EquipmentMapper;
import com.yanyuetong.service.IEquipmentCategoryService;
import com.yanyuetong.service.IEquipmentService;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.utils.CacheClient;
import com.yanyuetong.utils.SystemConstants;
import com.yanyuetong.vo.EquipmentVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.yanyuetong.utils.RedisConstants.CACHE_EQUIPMENT_KEY;
import static com.yanyuetong.utils.RedisConstants.CACHE_EQUIPMENT_TTL;
import static com.yanyuetong.utils.RedisConstants.CACHE_EQUIPMENT_TTL_JITTER;

/**
 * 设备管理服务。设备详情采用 Redis 互斥重建缓存，所有可改变详情的写操作在成功后删除缓存。
 */
@Service
public class EquipmentServiceImpl extends ServiceImpl<EquipmentMapper, Equipment> implements IEquipmentService {

    @Resource
    private ILaboratoryService laboratoryService;

    @Resource
    private IEquipmentCategoryService equipmentCategoryService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public IPage<EquipmentVO> queryEquipmentPage(EquipmentQueryDTO query) {
        Integer current = query.getCurrent() == null ? 1 : query.getCurrent();
        Page<EquipmentVO> page = new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE);
        return getBaseMapper().selectEquipmentPage(page, query);
    }

    /**
     * 查询设备详情。缓存未命中时通过互斥锁限制并发回源，并为不存在的设备写入短 TTL 空值。
     */
    @Override
    public EquipmentVO queryEquipmentById(Long id) {
        // 1、详情读走缓存；回源函数组装设备、实验室、分类信息，CacheClient 负责并发重建和空值缓存。
        // 预约提交中的 getById 则直接查 MySQL，两条读链路用途不同，避免缓存旧状态影响预约资格判断。
        return cacheClient.queryWithMutex(
                CACHE_EQUIPMENT_KEY,
                id,
                EquipmentVO.class,
                this::queryEquipmentByIdFromDb,
                equipmentCacheTtlMinutes(),
                TimeUnit.MINUTES);
    }

    @Override
    public Long saveEquipment(Equipment equipment) {
        laboratoryService.assertManageable(equipment.getLaboratoryId());
        save(equipment);
        return equipment.getId();
    }

    @Override
    public void updateEquipment(Equipment equipment) {
        // 1、检查原设备归属；若要调整实验室，目标实验室也必须归当前管理员管理。
        Equipment existing = getById(equipment.getId());
        assertEquipmentManageable(existing);
        // 禁止把设备"搬"进别人的实验室
        if (equipment.getLaboratoryId() != null
                && !equipment.getLaboratoryId().equals(existing.getLaboratoryId())) {
            laboratoryService.assertManageable(equipment.getLaboratoryId());
        }
        // 2、先更新 MySQL 再删缓存，使下一次详情读取重新组装；删除并非与 MySQL 同一事务原子提交。
        updateById(equipment);
        deleteEquipmentCache(equipment.getId());
    }

    @Override
    public void updateEquipmentStatus(Long id, EquipmentStatus status) {
        assertEquipmentManageable(getById(id));
        updateById(new Equipment().setId(id).setStatus(status));
        deleteEquipmentCache(id);
    }

    @Override
    public void deleteEquipment(Long id) {
        assertEquipmentManageable(getById(id));
        removeById(id);
        deleteEquipmentCache(id);
    }

    /** 设备不存在与不归当前用户管，统一按无权处理（不区分，避免泄露存在性） */
    private void assertEquipmentManageable(Equipment existing) {
        if (existing == null) {
            throw new ForbiddenException("无权管理该设备");
        }
        laboratoryService.assertManageable(existing.getLaboratoryId());
    }

    private EquipmentVO queryEquipmentByIdFromDb(Long id) {
        Equipment equipment = getById(id);
        if (equipment == null) {
            return null;
        }
        EquipmentVO equipmentVO = BeanUtil.copyProperties(equipment, EquipmentVO.class);
        Laboratory laboratory = laboratoryService.getById(equipment.getLaboratoryId());
        if (laboratory != null) {
            equipmentVO.setLaboratoryName(laboratory.getName());
            equipmentVO.setCampus(laboratory.getCampus());
            equipmentVO.setBuilding(laboratory.getBuilding());
            equipmentVO.setRoom(laboratory.getRoom());
        }
        EquipmentCategory category = equipmentCategoryService.getById(equipment.getCategoryId());
        if (category != null) {
            equipmentVO.setCategoryName(category.getName());
        }
        return equipmentVO;
    }

    private long equipmentCacheTtlMinutes() {
        // 在基础 TTL 上加随机抖动，降低大量设备缓存同时过期引发雪崩的概率。
        return CACHE_EQUIPMENT_TTL + RandomUtil.randomLong(0, CACHE_EQUIPMENT_TTL_JITTER);
    }

    private void deleteEquipmentCache(Long id) {
        stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + id);
    }
}
