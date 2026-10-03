package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.Laboratory;
import com.hmdp.mapper.LaboratoryMapper;
import com.hmdp.service.ILaboratoryService;
import org.springframework.stereotype.Service;

@Service
public class LaboratoryServiceImpl extends ServiceImpl<LaboratoryMapper, Laboratory> implements ILaboratoryService {
}
