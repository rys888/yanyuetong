package com.yanyuetong.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.dto.EquipmentQueryDTO;
import com.yanyuetong.entity.Equipment;
import com.yanyuetong.vo.EquipmentVO;
import org.apache.ibatis.annotations.Param;

public interface EquipmentMapper extends BaseMapper<Equipment> {

    IPage<EquipmentVO> selectEquipmentPage(IPage<EquipmentVO> page, @Param("q") EquipmentQueryDTO query);
}
