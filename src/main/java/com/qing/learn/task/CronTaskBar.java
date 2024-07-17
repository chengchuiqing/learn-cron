package com.qing.learn.task;

import com.qing.learn.service.IPollableService;
import org.springframework.stereotype.Service;


/**
 * 周期固定的任务，设它的Cron表达式永远不会发生变化
 */
//@Service
public class CronTaskBar implements IPollableService {
    @Override
    public void poll() {
        System.out.println("Say Bar");
    }
 
    @Override
    public String getCronExpression() {
        return "0/1 * * * * ?";
    }
}