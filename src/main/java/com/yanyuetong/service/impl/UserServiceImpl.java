package com.yanyuetong.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yanyuetong.dto.LoginFormDTO;
import com.yanyuetong.dto.Result;
import com.yanyuetong.dto.UserDTO;
import com.yanyuetong.entity.User;
import com.yanyuetong.enums.UserRole;
import com.yanyuetong.mapper.UserMapper;
import com.yanyuetong.service.IUserService;
import com.yanyuetong.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.yanyuetong.utils.RedisConstants.*;
import static com.yanyuetong.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户登录服务，使用 Redis 保存短期验证码和基于随机 Token 的服务端会话。
 * token 对应的用户摘要以 Hash 存储，便于拦截器直接恢复请求上下文而无需每次查库。
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、先校验手机号格式，避免为无效输入创建验证码缓存。
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        // 2、生成六位验证码，并按手机号保存到 Redis；TTL 限制它的使用时间。
        String code = RandomUtil.randomNumbers(6);

        // 登录接口使用同一手机号 Key 读取；实际存储在 Redis，未使用传入的 HttpSession。
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 3、当前实现仅通过 debug 日志输出验证码，尚未调用真实短信服务。
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    /**
     * 校验 Redis 验证码，必要时创建用户，然后创建带 TTL 的 token 会话。
     *
     * @return 成功时 data 为客户端后续放入 authorization 请求头的 token
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1、校验手机号，再读取 sendCode 写入 Redis 的验证码，只有匹配才继续查用户。
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        // 2、手机号作为用户查询条件；首次登录时创建普通 USER，角色不会来自客户端输入。
        User user = query().eq("phone", phone).one();

        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 3、生成随机 Token 作为会话索引，客户端后续原样放进 authorization 请求头。
        // 用户身份和角色保存在服务端 Hash 中，而不是编码在 Token 里。
        String token = UUID.randomUUID().toString(true);
        // 4、只保存 UserDTO 摘要；字段值转为字符串，以匹配 StringRedisTemplate 的序列化方式。
        // RefreshTokenInterceptor 会反向转换为 UserDTO，再存入该请求线程的 UserHolder。
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5、设置初始 TTL，随后由有效请求滑动续期；当前验证码校验后没有删除，仍按自身 TTL 失效。
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6、返回 Token 让客户端保存；登录方法本身不为后续请求建立 ThreadLocal 身份。
        return Result.ok(token);
    }

    @Override
    public void logout(String token) {
        // 删除当前 Token 对应的会话，使后续请求无法再恢复身份；同一用户的其他 Token 不受影响。
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setRole(UserRole.USER);
        // 2.保存用户
        save(user);
        return user;
    }
}
