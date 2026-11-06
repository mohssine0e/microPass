package com.micropass.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DockerServiceTest {

    @TempDir
    Path workspace;

    @Test
    void buildImage_rejectsDockerfileOutsideWorkspaceBeforeRunningDocker() {
        DockerService service = new DockerService("256m", "0.5", 128);
        List<String> logs = new ArrayList<>();

        DockerService.DockerBuildResult result = service.buildImage(
                workspace,
                "../Dockerfile",
                "micropass-demo",
                "abc1234",
                logs::add,
                new AtomicBoolean(false),
                new AtomicReference<>());

        assertThat(result.success()).isFalse();
        assertThat(result.logs()).contains("must stay inside");
        assertThat(logs).anyMatch(line -> line.contains("must stay inside"));
    }
}
