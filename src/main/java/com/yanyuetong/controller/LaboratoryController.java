package com.yanyuetong.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yanyuetong.annotation.RequireRole;
import com.yanyuetong.dto.Result;
import com.yanyuetong.entity.Laboratory;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.service.ILaboratoryService;
import com.yanyuetong.utils.SystemConstants;
import com.yanyuetong.vo.LaboratoryVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/laboratory")
public class LaboratoryController {

    @Resource
    private ILaboratoryService laboratoryService;

    @GetMapping("/{id}")
    public Result queryLaboratoryById(@PathVariable("id") Long id) {
        Laboratory laboratory = laboratoryService.getById(id);
        return Result.ok(laboratory == null ? null : toLaboratoryVO(laboratory));
    }

    @GetMapping("/list")
    public Result queryLaboratoryList(
            @RequestParam(value = "campus", required = false) String campus,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Laboratory> page = laboratoryService.query()
                .eq(StrUtil.isNotBlank(campus), "campus", campus)
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        List<LaboratoryVO> records = page.getRecords().stream()
                .map(this::toLaboratoryVO)
                .collect(Collectors.toList());
        return Result.ok(records, page.getTotal());
    }

    @PostMapping
    @RequireRole(UserRole.LAB_ADMIN)
    public Result saveLaboratory(@RequestBody Laboratory laboratory) {
        return Result.ok(laboratoryService.saveLaboratory(laboratory));
    }

    @PutMapping
    @RequireRole(UserRole.LAB_ADMIN)
    public Result updateLaboratory(@RequestBody Laboratory laboratory) {
        laboratoryService.updateLaboratory(laboratory);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result deleteLaboratory(@PathVariable("id") Long id) {
        laboratoryService.deleteLaboratory(id);
        return Result.ok();
    }

    private LaboratoryVO toLaboratoryVO(Laboratory laboratory) {
        return BeanUtil.copyProperties(laboratory, LaboratoryVO.class);
    }
}
