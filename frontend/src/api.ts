import { App, Deployment, CreateAppForm } from './types';

const API_BASE = 'http://localhost:9090/api';

export async function fetchApps(): Promise<App[]> {
  const res = await fetch(`${API_BASE}/apps`);
  if (!res.ok) throw new Error('Failed to fetch applications');
  return res.json();
}

export async function fetchAppStatus(id: number): Promise<{ running: boolean; allocatedPort?: number; activeContainerId?: string }> {
  const res = await fetch(`${API_BASE}/apps/${id}/status`);
  if (!res.ok) return { running: false };
  return res.json();
}

export async function fetchLatestDeployment(appId: number): Promise<Deployment | null> {
  const res = await fetch(`${API_BASE}/apps/${appId}/latest-deployment`);
  if (res.status === 204 || !res.ok) return null;
  return res.json();
}

export async function createApp(form: CreateAppForm): Promise<App> {
  const res = await fetch(`${API_BASE}/apps`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(form),
  });
  if (!res.ok) {
    const err = await res.text();
    throw new Error(err || 'Failed to create application');
  }
  return res.json();
}

export async function triggerDeploy(appId: number): Promise<Deployment> {
  const res = await fetch(`${API_BASE}/apps/${appId}/deploy`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error('Failed to trigger deployment');
  return res.json();
}

export async function cancelDeployment(deploymentId: number): Promise<void> {
  await fetch(`${API_BASE}/deployments/${deploymentId}/cancel`, {
    method: 'POST',
  });
}

export async function stopApp(appId: number): Promise<void> {
  const res = await fetch(`${API_BASE}/apps/${appId}/stop`, {
    method: 'POST',
  });
  if (!res.ok) throw new Error('Failed to stop application');
}

export async function deleteApp(appId: number): Promise<void> {
  const res = await fetch(`${API_BASE}/apps/${appId}`, {
    method: 'DELETE',
  });
  if (!res.ok) throw new Error('Failed to delete application');
}

export async function fetchBranches(gitUrl: string): Promise<string[]> {
  if (!gitUrl.trim()) return [];
  try {
    const res = await fetch(`${API_BASE}/git/branches?gitUrl=${encodeURIComponent(gitUrl.trim())}`);
    if (!res.ok) return [];
    return res.json();
  } catch {
    return [];
  }
}

export function getStreamUrl(deploymentId: number): string {
  return `${API_BASE}/deployments/${deploymentId}/logs/stream`;
}

