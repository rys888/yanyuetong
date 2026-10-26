package com.yanyuetong.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 登录门禁拦截器。它不再解析 token，只检查更早执行的 RefreshTokenInterceptor 是否已建立用户上下文。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、读取前置拦截器恢复的身份；无需再次访问 Redis。没有身份时返回 401 并终止处理。
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        // 2、这里只确认“已登录”，角色是否允许、资源是否归本人管理还要由后续代码检查。
        return true;
    }
}
