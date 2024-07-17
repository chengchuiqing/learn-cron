package com.qing.learn.task;

import com.qing.learn.service.IPollableService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 第三个任务就厉害了，它仿佛就像一个电灯的开关，在启用和禁用中反复横跳
 */
//@Service
public class CronTaskUnavailable implements IPollableService {
    private String cronExpression = "-";  // 表达式“-”则作为一个特殊的标记，用于禁用某个定时任务
    private static final Map<String, String> map = new HashMap<>();
 
    static {
        map.put("-", "0/1 * * * * ?");
        map.put("0/1 * * * * ?", "-");
    }
 
    @Override
    public void poll() {
        System.out.println("Say Unavailable");
    }
 
    @Override
    public String getCronExpression() {
        return (cronExpression = map.get(cronExpression));
    }
}