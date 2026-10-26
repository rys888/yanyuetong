package com.yanyuetong.controller;


import com.yanyuetong.dto.LoginFormDTO;
import com.yanyuetong.dto.Result;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.service.IUserService;
import com.yanyuetong.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * 登录 HTTP 入口：验证码登录创建 Redis Token 会话，后续请求由拦截器恢复身份。
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 服务当前将验证码写入 Redis 并输出 debug 日志，尚未调用真实短信服务。
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数；当前服务只校验手机号与验证码，未实现密码登录
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 返回的 data 是随机 Token；客户端保存后，后续请求用 authorization 请求头携带。
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(@RequestHeader(value = "authorization", required = false) String token) {
        // 1、删除服务端 Token 会话，使后续请求无法恢复登录身份。
        userService.logout(token);
        // 2、当前请求已被前置拦截器恢复过身份，这里同时清理；请求结束后还会有统一清理。
        UserHolder.removeUser();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me(){
        // 直接使用拦截器恢复的摘要，不再查 Redis；角色等字段来自登录时保存的会话快照。
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

}
