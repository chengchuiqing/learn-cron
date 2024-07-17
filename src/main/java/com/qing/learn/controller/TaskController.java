package com.qing.learn.controller;

import com.qing.learn.service.CronService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

    @Autowired
    private CronService cronService;

    /**
     * 更新任务
     */
    @GetMapping("/update")
    public String update() {
        cronService.cronTaskFooUpdate();
        return "update";
    }

    /**
     * 禁用任务
     */
    @GetMapping("/disable")
    public String disable() {
        cronService.cronTaskFooDisable();
        return "disable";
    }

}
