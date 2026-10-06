package com.yanyuetong.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yanyuetong.dto.LoginFormDTO;
import com.yanyuetong.dto.Result;
import com.yanyuetong.entity.User;

import javax.servlet.http.HttpSession;

public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    void logout(String token);

}
