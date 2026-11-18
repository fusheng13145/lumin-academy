package com.leyon.lumin.ai.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** MyBatis 扫描边界：只扫描显式 @Mapper 接口，避免把 AiRunStore 等出站端口误注册成 Mapper。 */
@Configuration
@MapperScan(basePackages = "com.leyon.lumin", annotationClass = Mapper.class)
public class PersistenceConfiguration {
}
