package com.yanyuetong.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yanyuetong.annotation.RequireRole;
import com.yanyuetong.dto.ReservationQueryDTO;
import com.yanyuetong.dto.ReservationSubmitDTO;
import com.yanyuetong.dto.Result;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.service.IReservationService;
import com.yanyuetong.vo.ReservationVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/reservation")
public class ReservationController {

    @Resource
    private IReservationService reservationService;

    @PostMapping
    public Result submit(@RequestBody ReservationSubmitDTO request) {
        return Result.ok(reservationService.submit(request.getBatchId()));
    }

    @GetMapping("/page")
    public Result queryMyPage(ReservationQueryDTO query) {
        IPage<ReservationVO> page = reservationService.queryMyPage(query);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @GetMapping("/{id}")
    public Result queryMine(@PathVariable("id") Long id) {
        return Result.ok(reservationService.queryMine(id));
    }

    @GetMapping("/manage/page")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result queryManagePage(ReservationQueryDTO query) {
        IPage<ReservationVO> page = reservationService.queryManagePage(query);
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @PostMapping("/{id}/cancel")
    public Result cancel(@PathVariable("id") Long id) {
        reservationService.cancel(id);
        return Result.ok();
    }

    @PostMapping("/{id}/verify")
    @RequireRole(UserRole.LAB_ADMIN)
    public Result verify(@PathVariable("id") Long id) {
        reservationService.verify(id);
        return Result.ok();
    }
}
