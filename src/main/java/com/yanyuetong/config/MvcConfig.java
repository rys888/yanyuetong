package com.yanyuetong.config;

import com.yanyuetong.utils.LoginInterceptor;
import com.yanyuetong.utils.RefreshTokenInterceptor;
import com.yanyuetong.utils.RoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

/**
 * Web 认证授权链配置。顺序必须为“恢复用户→登录门禁→角色校验”，
 * 否则后两个拦截器无法从 ThreadLocal 获得已验证身份。
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 实际执行顺序由 order 决定，而非下面的注册顺序：0 恢复用户→1 登录门禁→2 角色检查。
        // 1、登录门禁跳过验证码和登录接口，让匿名用户能够先取得 Token；其余接口要求已建立身份。
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login"
                ).order(1);
        // 2、此处虽然后注册，但 order(0) 最先执行：从 Redis 恢复 ThreadLocal，登录开放接口也能续期。
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
        // 3、order(2) 最后检查 @RequireRole；通过角色检查后，资源归属仍由业务 Service 校验。
        registry.addInterceptor(new RoleInterceptor()).addPathPatterns("/**").order(2);
    }
}
