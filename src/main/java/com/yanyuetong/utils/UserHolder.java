package com.yanyuetong.utils;

import com.yanyuetong.dto.UserDTO;

/**
 * 当前请求用户的 ThreadLocal 容器。该类不负责认证，只在拦截器与业务层之间传递已验证的用户摘要。
 * 同一线程可共享身份，不同线程不能自动共享；请求完成时由 RefreshTokenInterceptor 清理，容器不会自行过期。
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
