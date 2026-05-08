package com.nexa.flowops.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nexa.flowops.service.ServiceMgmtService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {

    private final ServiceMgmtService serviceMgmtService;

    public PageController(ServiceMgmtService serviceMgmtService) {
        this.serviceMgmtService = serviceMgmtService;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("role", StpUtil.hasRole("admin") ? "admin" : "user");
        return "dashboard";
    }

    @GetMapping("/services")
    public String services() {
        return "service-list";
    }

    @GetMapping("/services/create")
    public String serviceCreate() {
        return "service-edit";
    }

    @GetMapping("/services/{id}")
    public String serviceEdit(@PathVariable Long id, Model model) {
        model.addAttribute("service", serviceMgmtService.getById(id));
        return "service-edit";
    }

    @GetMapping("/logs")
    public String logs() {
        return "deploy-logs";
    }

    @GetMapping("/users")
    public String users() {
        return "user-list";
    }
}