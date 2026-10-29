package com.micropass.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceServiceTest {

    @TempDir
    Path tempBaseDir;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(tempBaseDir.toAbsolutePath().toString());
    }

    @Test
    void prepareAppWorkspace_createsSourceDirectory() {
        Long appId = 101L;
        Path appSourceDir = workspaceService.prepareAppWorkspace(appId);

        assertThat(Files.exists(appSourceDir)).isTrue();
        assertThat(appSourceDir.endsWith("source")).isTrue();
        assertThat(appSourceDir.getParent().endsWith("app-101")).isTrue();
    }

    @Test
    void cleanWorkspace_preservesSourceCache() throws IOException {
        Long appId = 102L;
        Path appSourceDir = workspaceService.prepareAppWorkspace(appId);
        Path dummyFile = appSourceDir.resolve("dummy.txt");
        Files.writeString(dummyFile, "cached content");

        workspaceService.cleanWorkspace(appId, 1L);

        // Source directory and cached files must still exist!
        assertThat(Files.exists(dummyFile)).isTrue();
    }

    @Test
    void cleanAppWorkspace_deletesEntireAppFolder() throws IOException {
        Long appId = 103L;
        Path appSourceDir = workspaceService.prepareAppWorkspace(appId);
        Path dummyFile = appSourceDir.resolve("dummy.txt");
        Files.writeString(dummyFile, "cached content");

        workspaceService.cleanAppWorkspace(appId);

        // Entire app directory must be removed
        assertThat(Files.exists(tempBaseDir.resolve("app-103"))).isFalse();
    }
}
