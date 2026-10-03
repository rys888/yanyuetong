package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.EquipmentQueryDTO;
import com.hmdp.entity.Equipment;
import com.hmdp.vo.EquipmentVO;
import org.apache.ibatis.annotations.Param;

public interface EquipmentMapper extends BaseMapper<Equipment> {

    IPage<EquipmentVO> selectEquipmentPage(IPage<EquipmentVO> page, @Param("q") EquipmentQueryDTO query);
}
