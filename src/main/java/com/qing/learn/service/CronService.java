package com.qing.learn.service;

import com.qing.learn.config.CronTaskFooConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CronService {

    @Autowired
    private CronTaskFooConfig cronTaskFooConfig;

    /**
     * 更新定时任务
     */
    public void cronTaskFooUpdate() {
        cronTaskFooConfig.setCron("0/5 * * * * ?");
        System.out.println("修改完毕");
    }

    /**
     * 禁用定时任务
     */
    public void cronTaskFooDisable() {
        cronTaskFooConfig.setCron("-");
        System.out.println("禁用完毕");
    }

}
