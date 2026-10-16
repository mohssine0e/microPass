package com.micropass.web;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.service.DeploymentPipelineService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ui")
public class DashboardViewController {

    private final AppRepository appRepository;
    private final DeploymentPipelineService pipelineService;

    public DashboardViewController(AppRepository appRepository, DeploymentPipelineService pipelineService) {
        this.appRepository = appRepository;
        this.pipelineService = pipelineService;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("apps", appRepository.findAll());
        return "dashboard";
    }

    @GetMapping("/apps/table")
    public String getAppsTable(Model model) {
        model.addAttribute("apps", appRepository.findAll());
        return "dashboard :: appsTable";
    }

    @PostMapping("/apps")
    public String createApp(
            @RequestParam String name,
            @RequestParam String gitUrl,
            @RequestParam(defaultValue = "main") String defaultBranch,
            Model model) {
        App app = App.builder()
                .name(name.trim().toLowerCase())
                .gitUrl(gitUrl.trim())
                .defaultBranch(defaultBranch.trim())
                .build();
        appRepository.save(app);

        model.addAttribute("apps", appRepository.findAll());
        return "dashboard :: appsTable";
    }

    @PostMapping("/apps/{id}/stop")
    public String stopApp(@PathVariable Long id, Model model) {
        pipelineService.stopApp(id);
        model.addAttribute("apps", appRepository.findAll());
        return "dashboard :: appsTable";
    }
}
