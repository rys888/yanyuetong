package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.annotation.RequireRole;
import com.hmdp.dto.Result;
import com.hmdp.entity.Laboratory;
import com.hmdp.enums.UserRole;
import com.hmdp.service.ILaboratoryService;
import com.hmdp.utils.SystemConstants;
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

@RestController
@RequestMapping("/laboratory")
public class LaboratoryController {

    @Resource
    private ILaboratoryService laboratoryService;

    @GetMapping("/{id}")
    public Result queryLaboratoryById(@PathVariable("id") Long id) {
        return Result.ok(laboratoryService.getById(id));
    }

    @GetMapping("/list")
    public Result queryLaboratoryList(
            @RequestParam(value = "campus", required = false) String campus,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        Page<Laboratory> page = laboratoryService.query()
                .eq(StrUtil.isNotBlank(campus), "campus", campus)
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
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
}
