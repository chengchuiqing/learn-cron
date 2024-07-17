package com.qing.learn.task;

import com.qing.learn.config.CronTaskFooConfig;
import com.qing.learn.service.IPollableService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Random;


/**
 * 经常更换执行周期的任务，我们用一个随机数发生器来模拟它的善变
 */
@Service
@Data
public class CronTaskFoo implements IPollableService {

    @Autowired
    private CronTaskFooConfig config;

    @Override
    public void poll() {
        System.out.println("Say Foo");
    }
 
    @Override
    public String getCronExpression() {
        return config.getCron();
    }
}