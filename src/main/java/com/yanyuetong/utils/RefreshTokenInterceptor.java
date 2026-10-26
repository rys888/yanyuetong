package com.yanyuetong.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.yanyuetong.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.yanyuetong.utils.RedisConstants.LOGIN_USER_KEY;
import static com.yanyuetong.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 认证上下文拦截器：在所有请求上尝试从 Redis token 恢复用户，并对有效会话滑动续期。
 * 它本身不拒绝匿名请求，只负责建立 ThreadLocal；是否必须登录由后续 LoginInterceptor 决定。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1、读取客户端登录后保存的 Token；它是 Redis 会话索引，不是用户信息本身。
        // 未携带 Token 时仍放行，由后面的 LoginInterceptor 判断该接口是否需要登录。
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 2、按登录时相同的 Key 读取 Redis Hash。空结果表示会话不存在或已过期，不能建立用户身份。
        String key  = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()) {
            return true;
        }
        // 3、把 Hash 中的字符串字段还原为 UserDTO，与 UserServiceImpl.login 的存储转换相对应。
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 4、放入当前线程的 ThreadLocal，后续拦截器和 Service 可通过 UserHolder.getUser() 共享身份。
        // 它不会自动传到 RabbitMQ 消费线程；预约消息必须自行携带 userId。
        UserHolder.saveUser(userDTO);
        // 5、为有效会话刷新 TTL：持续访问就续期，长时间不访问则自动过期。
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 6、继续执行登录门禁、角色校验和 Controller；此处 return true 本身不代表登录或授权成功。
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 请求完成后清理 ThreadLocal；Web 容器会复用线程，残留身份可能被下一个请求误用。
        UserHolder.removeUser();
    }
}
