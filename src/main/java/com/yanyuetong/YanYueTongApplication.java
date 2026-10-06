package com.yanyuetong;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.yanyuetong.mapper")
@SpringBootApplication
public class YanYueTongApplication {

    public static void main(String[] args) {
        SpringApplication.run(YanYueTongApplication.class, args);
    }

}
