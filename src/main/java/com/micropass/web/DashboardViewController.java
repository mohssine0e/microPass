package com.micropass.web;

import com.micropass.domain.App;
import com.micropass.domain.AppRepository;
import com.micropass.domain.Deployment;
import com.micropass.domain.DeploymentRepository;
import com.micropass.service.DeploymentPipelineService;
import com.micropass.service.GitService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/ui")
public class DashboardViewController {

    private final AppRepository appRepository;
    private final DeploymentRepository deploymentRepository;
    private final DeploymentPipelineService pipelineService;
    private final GitService gitService;

    public DashboardViewController(
            AppRepository appRepository,
            DeploymentRepository deploymentRepository,
            DeploymentPipelineService pipelineService,
            GitService gitService) {
        this.appRepository = appRepository;
        this.deploymentRepository = deploymentRepository;
        this.pipelineService = pipelineService;
        this.gitService = gitService;
    }

    private void populateModel(Model model) {
        List<App> apps = appRepository.findAll();
        Map<Long, Deployment> latestDeployments = new HashMap<>();
        for (App app : apps) {
            deploymentRepository.findTopByAppIdOrderByIdDesc(app.getId())
                    .ifPresent(d -> latestDeployments.put(app.getId(), d));
        }
        model.addAttribute("apps", apps);
        model.addAttribute("latestDeployments", latestDeployments);
    }

    @GetMapping
    public String index(Model model) {
        populateModel(model);
        return "dashboard";
    }

    @GetMapping("/apps/table")
    public String getAppsTable(Model model) {
        populateModel(model);
        return "dashboard :: appsTable";
    }

    @GetMapping("/git/branches")
    public String fetchBranches(@RequestParam String gitUrl, Model model) {
        List<String> branches = gitService.listRemoteBranches(gitUrl);
        model.addAttribute("branches", branches);
        return "dashboard :: branchOptions";
    }

    @PostMapping("/apps")
    public String createApp(
            @RequestParam String name,
            @RequestParam String gitUrl,
            @RequestParam String defaultBranch,
            @RequestParam(defaultValue = "Dockerfile") String dockerfilePath,
            @RequestParam(defaultValue = "80") Integer targetPort,
            Model model) {
        try {
            App app = App.builder()
                    .name(name.trim().toLowerCase())
                    .gitUrl(gitUrl.trim())
                    .defaultBranch(defaultBranch != null && !defaultBranch.isBlank() ? defaultBranch.trim() : "master")
                    .dockerfilePath(dockerfilePath != null && !dockerfilePath.isBlank() ? dockerfilePath.trim() : "Dockerfile")
                    .targetPort(targetPort != null ? targetPort : 80)
                    .build();
            appRepository.save(app);
        } catch (Exception e) {
            model.addAttribute("formError", "Failed to register project: " + e.getMessage());
        }

        populateModel(model);
        return "dashboard :: appsTable";
    }

    @PostMapping("/apps/{id}/stop")
    public String stopApp(@PathVariable Long id, Model model) {
        pipelineService.stopApp(id);
        populateModel(model);
        return "dashboard :: appsTable";
    }
}
