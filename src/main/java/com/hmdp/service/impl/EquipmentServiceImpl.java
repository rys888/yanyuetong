package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.EquipmentQueryDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.entity.EquipmentCategory;
import com.hmdp.entity.Laboratory;
import com.hmdp.enums.EquipmentStatus;
import com.hmdp.exception.ForbiddenException;
import com.hmdp.mapper.EquipmentMapper;
import com.hmdp.service.IEquipmentCategoryService;
import com.hmdp.service.IEquipmentService;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import com.hmdp.vo.EquipmentVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_EQUIPMENT_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_EQUIPMENT_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_EQUIPMENT_TTL_JITTER;

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

    @Override
    public EquipmentVO queryEquipmentById(Long id) {
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
        Equipment existing = getById(equipment.getId());
        assertEquipmentManageable(existing);
        // 禁止把设备"搬"进别人的实验室
        if (equipment.getLaboratoryId() != null
                && !equipment.getLaboratoryId().equals(existing.getLaboratoryId())) {
            laboratoryService.assertManageable(equipment.getLaboratoryId());
        }
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
        return CACHE_EQUIPMENT_TTL + RandomUtil.randomLong(0, CACHE_EQUIPMENT_TTL_JITTER);
    }

    private void deleteEquipmentCache(Long id) {
        stringRedisTemplate.delete(CACHE_EQUIPMENT_KEY + id);
    }
}
