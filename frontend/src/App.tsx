import { useEffect, useState, useCallback } from 'react';
import { Navbar } from './components/Navbar';
import { AppCard } from './components/AppCard';
import { CreateAppModal } from './components/CreateAppModal';
import { LogConsoleModal } from './components/LogConsoleModal';
import type { App as AppType, DeploymentStatus } from './types';
import { fetchApps, fetchLatestDeployment, fetchAppStatus } from './api';
import { Plus, Server, RefreshCw } from 'lucide-react';

export function App() {
  const [apps, setApps] = useState<AppType[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [activeConsole, setActiveConsole] = useState<{
    deploymentId: number;
    appName: string;
    status: DeploymentStatus;
  } | null>(null);

  const loadData = useCallback(async () => {
    try {
      const rawApps = await fetchApps();
      const enriched = await Promise.all(
        rawApps.map(async (a) => {
          const [latest, status] = await Promise.all([
            fetchLatestDeployment(a.id),
            fetchAppStatus(a.id),
          ]);
          return {
            ...a,
            latestDeployment: latest || undefined,
            running: status.running,
            allocatedPort: status.allocatedPort || a.allocatedPort,
          };
        })
      );
      setApps(enriched);
    } catch (e) {
      console.error('Failed to load apps', e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
    // Auto-refresh interval every 3 seconds to keep build and container statuses live
    const interval = setInterval(loadData, 3000);
    return () => clearInterval(interval);
  }, [loadData]);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col font-sans">
      <Navbar onNewApp={() => setShowCreateModal(true)} appCount={apps.length} />

      <main className="flex-1 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 w-full">
        {/* Top Header / Actions */}
        <div className="flex items-center justify-between mb-8">
          <div>
            <h2 className="text-2xl font-bold tracking-tight text-white">Applications</h2>
            <p className="text-sm text-slate-400 mt-1">
              Deploy from any Git repository directly into isolated local Docker containers.
            </p>
          </div>

          <button
            onClick={() => {
              setLoading(true);
              loadData();
            }}
            className="flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-slate-900 border border-slate-800 hover:border-slate-700 text-slate-300 text-xs font-medium transition"
            title="Refresh dashboard"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin text-cyan-400' : ''}`} />
            <span>Refresh</span>
          </button>
        </div>

        {/* Content Body */}
        {loading && apps.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-slate-500 space-y-3">
            <RefreshCw className="h-6 w-6 animate-spin text-cyan-400" />
            <p className="text-sm">Connecting to microPass Engine...</p>
          </div>
        ) : apps.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-800 bg-slate-900/30 p-12 text-center max-w-md mx-auto my-12">
            <div className="h-12 w-12 rounded-xl bg-slate-800 text-slate-400 flex items-center justify-center mx-auto mb-4 border border-slate-700">
              <Server className="h-6 w-6" />
            </div>
            <h3 className="text-base font-semibold text-white">No applications registered</h3>
            <p className="text-xs text-slate-400 mt-1 mb-6 leading-relaxed">
              Register your first Git repository to build and run containerized apps locally.
            </p>
            <button
              onClick={() => setShowCreateModal(true)}
              className="inline-flex items-center space-x-2 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-xs font-semibold px-4 py-2 rounded-lg shadow-md shadow-cyan-500/20 transition active:scale-95"
            >
              <Plus className="h-4 w-4" />
              <span>Register First App</span>
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {apps.map((app) => (
              <AppCard
                key={app.id}
                app={app}
                onOpenLogs={(depId, name, status) =>
                  setActiveConsole({ deploymentId: depId, appName: name, status })
                }
                onRefresh={loadData}
              />
            ))}
          </div>
        )}
      </main>

      {/* Modals */}
      {showCreateModal && (
        <CreateAppModal onClose={() => setShowCreateModal(false)} onCreated={loadData} />
      )}

      {activeConsole && (
        <LogConsoleModal
          deploymentId={activeConsole.deploymentId}
          appName={activeConsole.appName}
          initialStatus={activeConsole.status}
          onClose={() => setActiveConsole(null)}
          onStatusChange={loadData}
        />
      )}
    </div>
  );
}

export default App;

