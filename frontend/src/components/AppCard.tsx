import { useState } from 'react';
import {
  ArrowUpRight,
  Box,
  GitBranch,
  GitCommitHorizontal,
  Loader2,
  Network,
  Play,
  Square,
  Terminal,
  Trash2,
} from 'lucide-react';
import type { App, DeploymentStatus } from '../types';
import { deleteApp, stopApp, triggerDeploy } from '../api';
import { getEffectiveStatus, getStatusLabel, getStatusTone, isDeploymentActive } from '../status';

interface AppCardProps {
  app: App;
  onOpenLogs: (deploymentId: number, appName: string, status: DeploymentStatus) => void;
  onRefresh: () => void;
  onError: (message: string) => void;
}

const pipelineSteps: DeploymentStatus[] = ['QUEUED', 'CLONING', 'BUILDING', 'DEPLOYING'];

export function AppCard({ app, onOpenLogs, onRefresh, onError }: AppCardProps) {
  const [deploying, setDeploying] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const deployment = app.latestDeployment;
  const status = getEffectiveStatus(app);
  const isBuilding = isDeploymentActive(status);
  const statusTone = getStatusTone(status, Boolean(app.running));
  const liveHost = window.location.hostname || 'localhost';
  const initials = app.name.slice(0, 2).toUpperCase();
  const activeStep = pipelineSteps.indexOf(status);

  const handleDeploy = async () => {
    setDeploying(true);
    try {
      const nextDeployment = await triggerDeploy(app.id);
      onOpenLogs(nextDeployment.id, app.name, nextDeployment.status);
      onRefresh();
    } catch (caught: unknown) {
      onError(caught instanceof Error ? caught.message : 'Failed to trigger deployment');
    } finally {
      setDeploying(false);
    }
  };

  const handleStop = async () => {
    setStopping(true);
    try {
      await stopApp(app.id);
      onRefresh();
    } catch (caught: unknown) {
      onError(caught instanceof Error ? caught.message : 'Failed to stop application');
    } finally {
      setStopping(false);
    }
  };

  const handleDelete = async () => {
    if (!window.confirm(`Delete “${app.name}”, its deployment history, and its managed workspace?`)) return;
    setDeleting(true);
    try {
      await deleteApp(app.id);
      onRefresh();
    } catch (caught: unknown) {
      onError(caught instanceof Error ? caught.message : 'Failed to delete application');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <article className={`application-card application-card--${statusTone}`}>
      <div className="application-card__accent" aria-hidden="true" />
      <header className="application-card__header">
        <div className="application-identity">
          <span className="application-identity__mark">{initials}</span>
          <div>
            <div className="application-identity__name-row">
              <h3>{app.name}</h3>
              {app.allocatedPort && app.running && (
                <a
                  href={app.stableUrl || `http://${liveHost}:${app.allocatedPort}`}
                  target="_blank"
                  rel="noreferrer"
                  className="live-link"
                  title={app.stableUrl ? `Open stable URL: ${app.stableUrl}` : 'Open running application'}
                >
                  Open <ArrowUpRight className="h-3.5 w-3.5" />
                </a>
              )}
            </div>
            <p title={app.gitUrl}>{app.gitUrl}</p>
          </div>
        </div>
        <span className={`status-pill status-pill--${statusTone}`}>
          {isBuilding ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <i />}
          {getStatusLabel(status, Boolean(app.running))}
        </span>
      </header>

      {isBuilding && (
        <div className="pipeline-progress" aria-label={`Deployment is ${getStatusLabel(status)}`}>
          <div className="pipeline-progress__copy">
            <span>Deployment #{deployment?.id}</span>
            <strong>{getStatusLabel(status)}</strong>
          </div>
          <div className="pipeline-progress__track">
            {pipelineSteps.map((step, index) => (
              <span key={step} className={index <= activeStep ? 'is-complete' : ''} />
            ))}
          </div>
          <div className="pipeline-progress__labels">
            <span>Queue</span><span>Source</span><span>Build</span><span>Run</span>
          </div>
        </div>
      )}

      <div className="application-facts">
        <div className="application-fact">
          <span><GitBranch className="h-3.5 w-3.5" />Branch</span>
          <strong>{app.defaultBranch}</strong>
        </div>
        <div className="application-fact">
          <span><Network className="h-3.5 w-3.5" />Routing</span>
          <strong title={app.stableUrl || undefined}>
            {app.stableUrl ? (
              <a href={app.stableUrl} target="_blank" rel="noreferrer">
                {new URL(app.stableUrl).host}
              </a>
            ) : app.allocatedPort ? `:${app.allocatedPort}` : 'Unassigned'}
            <small> → :{app.targetPort || 80}</small>
          </strong>
        </div>
        <div className="application-fact">
          <span><GitCommitHorizontal className="h-3.5 w-3.5" />Revision</span>
          <strong className="is-mono">{deployment?.commitHash?.slice(0, 8) || 'Not deployed'}</strong>
        </div>
      </div>

      <div className="application-card__deployment">
        <div>
          <span><Box className="h-3.5 w-3.5" />Latest deployment</span>
          <strong>{deployment ? `Run #${deployment.id}` : 'No deployment yet'}</strong>
        </div>
        {deployment?.imageTag && <code title={deployment.imageTag}>{deployment.imageTag}</code>}
      </div>

      <footer className="application-card__actions">
        <button
          type="button"
          onClick={handleDeploy}
          disabled={deploying || isBuilding}
          className="button button--primary application-card__deploy"
        >
          {deploying || isBuilding ? <Loader2 className="h-4 w-4 animate-spin" /> : <Play className="h-4 w-4" />}
          <span>{isBuilding ? 'Deployment active' : deployment ? 'Deploy again' : 'Deploy now'}</span>
        </button>

        {deployment && (
          <button
            type="button"
            onClick={() => onOpenLogs(deployment.id, app.name, status)}
            className="button button--secondary application-card__logs"
          >
            <Terminal className="h-4 w-4" />
            <span>Logs</span>
          </button>
        )}

        {app.running && (
          <button
            type="button"
            onClick={handleStop}
            disabled={stopping || isBuilding}
            className="icon-action icon-action--warning"
            title={isBuilding ? 'Cancel the deployment before stopping the app' : 'Stop container'}
            aria-label="Stop container"
          >
            {stopping ? <Loader2 className="h-4 w-4 animate-spin" /> : <Square className="h-4 w-4" />}
          </button>
        )}

        <button
          type="button"
          onClick={handleDelete}
          disabled={deleting}
          className="icon-action icon-action--danger"
          title="Delete application"
          aria-label="Delete application"
        >
          {deleting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
        </button>
      </footer>
    </article>
  );
}
