package com.hmdp.controller;

import com.hmdp.dto.ReservationSubmitDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IReservationService;
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
}
