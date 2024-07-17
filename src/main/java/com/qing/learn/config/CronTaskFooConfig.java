package com.qing.learn.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
public class CronTaskFooConfig {

    @Value("${cron.expression:0/1 * * * * ?}")
    private String cron;

}
