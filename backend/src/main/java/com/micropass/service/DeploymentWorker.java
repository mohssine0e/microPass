package com.micropass.service;

import com.micropass.config.RabbitConfig;
import com.micropass.service.event.DeploymentTriggerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DeploymentWorker {

    private static final Logger log = LoggerFactory.getLogger(DeploymentWorker.class);

    private final DeploymentPipelineService pipelineService;

    public DeploymentWorker(DeploymentPipelineService pipelineService) {
        this.pipelineService = pipelineService;
    }

    @RabbitListener(queues = RabbitConfig.DEPLOYMENT_QUEUE)
    public void handleDeployment(DeploymentTriggerEvent event) {
        log.info("Received DeploymentTriggerEvent from RabbitMQ: deploymentId={}, appId={}, appName={}",
                event.deploymentId(), event.appId(), event.appName());

        // Keep processing inside the listener invocation. RabbitMQ acknowledges the
        // message only after this method returns, so a JVM crash does not silently
        // lose a deployment that merely started another background thread.
        pipelineService.runPipeline(event.appId(), event.deploymentId());
    }
}
