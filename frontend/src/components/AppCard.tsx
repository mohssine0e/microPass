import React, { useState } from 'react';
import { Play, Terminal, Square, Trash2, ExternalLink, GitBranch, Loader2 } from 'lucide-react';
import type { App, DeploymentStatus } from '../types';
import { triggerDeploy, stopApp, deleteApp } from '../api';

interface AppCardProps {
  app: App;
  onOpenLogs: (deploymentId: number, appName: string, status: DeploymentStatus) => void;
  onRefresh: () => void;
  onError: (message: string) => void;
}

export const AppCard: React.FC<AppCardProps> = ({ app, onOpenLogs, onRefresh, onError }) => {
  const [deploying, setDeploying] = useState(false);
  const [stopping, setStopping] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const deployment = app.latestDeployment;
  const status = deployment?.status || (app.running ? 'HEALTHY' : 'STOPPED');
  const isBuilding = status === 'QUEUED' || status === 'CLONING' || status === 'BUILDING' || status === 'DEPLOYING';
  const liveHost = window.location.hostname || 'localhost';

  const handleDeploy = async () => {
    setDeploying(true);
    try {
      const dep = await triggerDeploy(app.id);
      onOpenLogs(dep.id, app.name, dep.status);
      onRefresh();
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : 'Failed to trigger deployment');
    } finally {
      setDeploying(false);
    }
  };

  const handleStop = async () => {
    setStopping(true);
    try {
      await stopApp(app.id);
      onRefresh();
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : 'Failed to stop application');
    } finally {
      setStopping(false);
    }
  };

  const handleDelete = async () => {
    if (!window.confirm(`Are you sure you want to delete "${app.name}" and all its workspace data?`)) return;
    setDeleting(true);
    try {
      await deleteApp(app.id);
      onRefresh();
    } catch (e: unknown) {
      onError(e instanceof Error ? e.message : 'Failed to delete application');
    } finally {
      setDeleting(false);
    }
  };

  const getStatusBadge = () => {
    if (isBuilding) {
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-blue-500/10 text-blue-400 border border-blue-500/20">
          <Loader2 className="h-3 w-3 animate-spin text-blue-400" />
          {status}...
        </span>
      );
    }
    if (app.running) {
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
          <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
          {status === 'FAILED' ? 'Running · last deploy failed' : 'Running (Healthy)'}
        </span>
      );
    }
    if (status === 'FAILED') {
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-rose-500/10 text-rose-400 border border-rose-500/20">
          <span className="h-1.5 w-1.5 rounded-full bg-rose-400" />
          Failed
        </span>
      );
    }
    if (status === 'CANCELLED') {
      return (
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-semibold bg-amber-500/10 text-amber-400 border border-amber-500/20">
          <span className="h-1.5 w-1.5 rounded-full bg-amber-400" />
          Cancelled
        </span>
      );
    }
    return (
      <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium bg-slate-800 text-slate-400 border border-slate-700">
        <span className="h-1.5 w-1.5 rounded-full bg-slate-500" />
        Stopped
      </span>
    );
  };

  return (
    <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-5 hover:border-slate-700 transition shadow-lg hover:shadow-cyan-500/5 flex flex-col justify-between">
      <div>
        {/* Card Header */}
        <div className="flex items-start justify-between gap-3 mb-3">
          <div>
            <h3 className="text-base font-bold text-white tracking-tight flex items-center gap-2">
              {app.name}
              {app.allocatedPort && app.running && (
                <a
                  href={`http://${liveHost}:${app.allocatedPort}`}
                  target="_blank"
                  rel="noreferrer"
                  className="text-cyan-400 hover:text-cyan-300 transition"
                  title="Open live container"
                >
                  <ExternalLink className="h-3.5 w-3.5" />
                </a>
              )}
            </h3>
            <p className="text-xs text-slate-400 truncate max-w-xs mt-0.5" title={app.gitUrl}>
              {app.gitUrl}
            </p>
          </div>
          {getStatusBadge()}
        </div>

        {/* Metadata Grid */}
        <div className="grid grid-cols-2 gap-2 my-4 text-xs">
          <div className="bg-slate-950/60 p-2.5 rounded-lg border border-slate-800/80">
            <span className="text-slate-500 block text-[11px]">Branch</span>
            <span className="text-slate-300 font-mono flex items-center gap-1 mt-0.5">
              <GitBranch className="h-3 w-3 text-cyan-400" />
              {app.defaultBranch}
            </span>
          </div>

          <div className="bg-slate-950/60 p-2.5 rounded-lg border border-slate-800/80">
            <span className="text-slate-500 block text-[11px]">Port Mapping</span>
            <span className="text-slate-300 font-mono mt-0.5 block">
              {app.allocatedPort ? (
                <span className="text-emerald-400 font-semibold">:{app.allocatedPort}</span>
              ) : (
                <span className="text-slate-500">—</span>
              )}
              <span className="text-slate-500 text-[10px]"> → :{app.targetPort || 80}</span>
            </span>
          </div>
        </div>

        {/* Commit / Image Tag info if available */}
        {deployment && (
          <div className="text-[11px] text-slate-400 flex items-center justify-between border-t border-slate-800/60 pt-2.5 mb-4">
            <span className="font-mono">
              Commit:{' '}
              <span className="text-slate-300 font-semibold">
                {deployment.commitHash ? deployment.commitHash.substring(0, 7) : 'latest'}
              </span>
            </span>
            <span className="text-slate-500">Run #{deployment.id}</span>
          </div>
        )}
      </div>

      {/* Action Buttons */}
      <div className="flex items-center gap-2 pt-2 border-t border-slate-800/80">
        <button
          onClick={handleDeploy}
          disabled={deploying || isBuilding}
          className="flex-1 flex items-center justify-center space-x-1.5 py-2 px-3 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-xs font-semibold shadow-md shadow-cyan-500/10 transition active:scale-95 disabled:opacity-50"
        >
          {deploying || isBuilding ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
          <span>{isBuilding ? 'Building...' : 'Deploy'}</span>
        </button>

        {deployment && (
          <button
            onClick={() => onOpenLogs(deployment.id, app.name, status)}
            className="flex items-center justify-center space-x-1.5 py-2 px-3 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium border border-slate-700 transition"
            title="View Logs"
          >
            <Terminal className="h-3.5 w-3.5 text-cyan-400" />
            <span>Logs</span>
          </button>
        )}

        {app.running && (
          <button
            onClick={handleStop}
            disabled={stopping || isBuilding}
            className="p-2 rounded-lg bg-slate-800 hover:bg-rose-500/20 text-slate-400 hover:text-rose-400 border border-slate-700 transition disabled:cursor-not-allowed disabled:opacity-40"
            title={isBuilding ? 'Cancel the active deployment before stopping the app' : 'Stop Container'}
          >
            {stopping ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Square className="h-3.5 w-3.5" />}
          </button>
        )}

        <button
          onClick={handleDelete}
          disabled={deleting}
          className="p-2 rounded-lg bg-slate-800 hover:bg-rose-500/20 text-slate-400 hover:text-rose-400 border border-slate-700 transition"
          title="Delete App"
        >
          {deleting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
        </button>
      </div>
    </div>
  );
};
