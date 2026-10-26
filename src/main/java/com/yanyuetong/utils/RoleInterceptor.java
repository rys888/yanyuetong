package com.yanyuetong.utils;

import com.yanyuetong.annotation.RequireRole;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.enums.UserRole;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 声明式角色权限拦截器。仅处理标注 {@link RequireRole} 的控制器方法或类，
 * 未登录返回 401，已登录但角色不匹配返回 403。
 */
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、只检查 Controller 方法；静态资源等处理器不适用方法上的角色注解。
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 2、先找方法注解，再找类注解；均未声明时不追加角色限制，登录检查仍由前置拦截器负责。
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            // 方法级声明优先，未声明时才回退到类级角色要求。
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }

        // 3、从同一请求线程取得身份。匿名是 401，已登录但角色不符才是下面的 403。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(401);
            return false;
        }

        // 4、命中任一允许角色即放行。LAB_ADMIN 只代表管理员角色，不代表可以管理任意实验室；
        // 设备、批次等 Service 还会通过 LaboratoryService.assertManageable 检查资源归属。
        for (UserRole role : requireRole.value()) {
            if (role == user.getRole()) {
                return true;
            }
        }

        response.setStatus(403);
        return false;
    }
}
