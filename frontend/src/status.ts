import type { App, DeploymentStatus } from './types';

export const ACTIVE_DEPLOYMENT_STATUSES: DeploymentStatus[] = [
  'QUEUED',
  'CLONING',
  'BUILDING',
  'DEPLOYING',
];

export function getEffectiveStatus(app: App): DeploymentStatus {
  return app.latestDeployment?.status || (app.running ? 'HEALTHY' : 'STOPPED');
}

export function isDeploymentActive(status: DeploymentStatus): boolean {
  return ACTIVE_DEPLOYMENT_STATUSES.includes(status);
}

export function getStatusLabel(status: DeploymentStatus, running = false): string {
  if (running && status === 'FAILED') return 'Running · deploy failed';

  const labels: Record<DeploymentStatus, string> = {
    QUEUED: 'Queued',
    CLONING: 'Syncing source',
    BUILDING: 'Building image',
    DEPLOYING: 'Starting container',
    HEALTHY: 'Healthy',
    FAILED: 'Failed',
    CANCELLED: 'Cancelled',
    STOPPED: 'Stopped',
  };

  return labels[status];
}

export function getStatusTone(status: DeploymentStatus, running = false): 'healthy' | 'active' | 'danger' | 'warning' | 'neutral' {
  if (isDeploymentActive(status)) return 'active';
  if (running && status === 'FAILED') return 'warning';
  if (status === 'HEALTHY') return 'healthy';
  if (status === 'FAILED') return 'danger';
  if (status === 'CANCELLED') return 'warning';
  return 'neutral';
}
