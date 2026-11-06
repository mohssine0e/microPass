import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Boxes,
  CheckCircle2,
  Container,
  GitCommitHorizontal,
  Layers3,
  Plus,
  RefreshCw,
  Search,
  Server,
  ShieldCheck,
  X,
} from 'lucide-react';
import { Navbar } from './components/Navbar';
import { AppCard } from './components/AppCard';
import { CreateAppModal } from './components/CreateAppModal';
import { LogConsoleModal } from './components/LogConsoleModal';
import type { App as AppType, DeploymentStatus } from './types';
import { fetchApps, fetchLatestDeployment, fetchAppStatus } from './api';
import { getEffectiveStatus, isDeploymentActive } from './status';

type AppFilter = 'all' | 'running' | 'deploying' | 'attention' | 'stopped';

const filters: Array<{ id: AppFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'running', label: 'Running' },
  { id: 'deploying', label: 'Deploying' },
  { id: 'attention', label: 'Needs attention' },
  { id: 'stopped', label: 'Stopped' },
];

function matchesFilter(app: AppType, filter: AppFilter): boolean {
  const status = getEffectiveStatus(app);
  if (filter === 'running') return Boolean(app.running);
  if (filter === 'deploying') return isDeploymentActive(status);
  if (filter === 'attention') return status === 'FAILED' || status === 'CANCELLED';
  if (filter === 'stopped') return !app.running && !isDeploymentActive(status);
  return true;
}

export function App() {
  const [apps, setApps] = useState<AppType[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [engineReachable, setEngineReachable] = useState(true);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeFilter, setActiveFilter] = useState<AppFilter>('all');
  const [activeConsole, setActiveConsole] = useState<{
    deploymentId: number;
    appName: string;
    status: DeploymentStatus;
  } | null>(null);

  const loadData = useCallback(async (foreground = false) => {
    if (foreground) setRefreshing(true);
    try {
      const rawApps = await fetchApps();
      const enriched = await Promise.all(
        rawApps.map(async (app) => {
          const [latest, status] = await Promise.all([
            fetchLatestDeployment(app.id),
            fetchAppStatus(app.id),
          ]);
          return {
            ...app,
            latestDeployment: latest || undefined,
            running: status.running,
            allocatedPort: status.allocatedPort || app.allocatedPort,
          };
        }),
      );
      setApps(enriched);
      setEngineReachable(true);
      setLastUpdated(new Date());
      setError(null);
    } catch (caught: unknown) {
      setEngineReachable(false);
      setError(caught instanceof Error ? caught.message : 'Failed to load applications');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    void loadData();
    const interval = window.setInterval(() => void loadData(), 3000);
    return () => window.clearInterval(interval);
  }, [loadData]);

  const summary = useMemo(() => {
    const running = apps.filter((app) => app.running).length;
    const deploying = apps.filter((app) => isDeploymentActive(getEffectiveStatus(app))).length;
    const attention = apps.filter((app) => {
      const status = getEffectiveStatus(app);
      return status === 'FAILED' || status === 'CANCELLED';
    }).length;
    return { running, deploying, attention };
  }, [apps]);

  const visibleApps = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    return apps.filter((app) => {
      const searchable = `${app.name} ${app.gitUrl} ${app.defaultBranch}`.toLowerCase();
      return matchesFilter(app, activeFilter) && (!query || searchable.includes(query));
    });
  }, [activeFilter, apps, searchQuery]);

  const lastSyncLabel = lastUpdated
    ? lastUpdated.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : 'Waiting for first sync';

  return (
    <div className="product-shell">
      <div className="product-ambient product-ambient--one" aria-hidden="true" />
      <div className="product-ambient product-ambient--two" aria-hidden="true" />
      <div className="product-grid" aria-hidden="true" />

      <Navbar
        onNewApp={() => setShowCreateModal(true)}
        appCount={apps.length}
        engineReachable={engineReachable}
      />

      <main className="dashboard-container">
        <section className="dashboard-hero" aria-labelledby="dashboard-title">
          <div className="dashboard-hero__copy">
            <div className="section-kicker">
              <ShieldCheck className="h-3.5 w-3.5" aria-hidden="true" />
              Trusted single-host deployments
            </div>
            <h1 id="dashboard-title">
              Ship code with a clear view of <span>every deployment.</span>
            </h1>
            <p>
              Register a Git source, build an isolated image, verify readiness, and keep the last healthy
              application running—all from one local control plane.
            </p>
            <div className="dashboard-hero__actions">
              <button type="button" className="button button--primary" onClick={() => setShowCreateModal(true)}>
                <Plus className="h-4 w-4" aria-hidden="true" />
                Register application
              </button>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => document.getElementById('applications')?.scrollIntoView({ behavior: 'smooth' })}
              >
                View applications
                <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          </div>

          <aside className="platform-pulse" aria-label="Platform connection summary">
            <div className="platform-pulse__header">
              <div>
                <span>Platform pulse</span>
                <strong>{engineReachable ? 'Control plane reachable' : 'Connection degraded'}</strong>
              </div>
              <span className={`pulse-indicator ${engineReachable ? 'is-online' : 'is-offline'}`}>
                <i /> {engineReachable ? 'Online' : 'Offline'}
              </span>
            </div>
            <div className="platform-pulse__details">
              <div>
                <span>Refresh cadence</span>
                <strong>Every 3 seconds</strong>
              </div>
              <div>
                <span>Execution target</span>
                <strong>Local Docker</strong>
              </div>
              <div>
                <span>Last synchronized</span>
                <strong>{lastSyncLabel}</strong>
              </div>
            </div>
            <div className="deployment-flow" aria-label="Deployment flow">
              <span><GitCommitHorizontal className="h-3.5 w-3.5" />Git source</span>
              <i />
              <span><Layers3 className="h-3.5 w-3.5" />Build image</span>
              <i />
              <span><CheckCircle2 className="h-3.5 w-3.5" />Readiness</span>
              <i />
              <span><Container className="h-3.5 w-3.5" />Run</span>
            </div>
          </aside>
        </section>

        <section className="metric-grid" aria-label="Application summary">
          <article className="metric-card">
            <span className="metric-card__icon metric-card__icon--violet"><Boxes /></span>
            <div><span>Registered</span><strong>{apps.length}</strong><small>Total application definitions</small></div>
          </article>
          <article className="metric-card">
            <span className="metric-card__icon metric-card__icon--green"><Activity /></span>
            <div><span>Running</span><strong>{summary.running}</strong><small>Containers currently active</small></div>
          </article>
          <article className="metric-card">
            <span className="metric-card__icon metric-card__icon--blue"><Server /></span>
            <div><span>In progress</span><strong>{summary.deploying}</strong><small>Queued, building, or deploying</small></div>
          </article>
          <article className="metric-card">
            <span className="metric-card__icon metric-card__icon--amber"><AlertTriangle /></span>
            <div><span>Needs attention</span><strong>{summary.attention}</strong><small>Failed or cancelled attempts</small></div>
          </article>
        </section>

        {error && (
          <div className="product-alert" role="alert">
            <span className="product-alert__icon"><AlertTriangle className="h-5 w-5" /></span>
            <div>
              <strong>microPass could not synchronize the dashboard.</strong>
              <p>{error}</p>
            </div>
            <button type="button" onClick={() => setError(null)} aria-label="Dismiss error">
              <X className="h-4 w-4" />
            </button>
          </div>
        )}

        <section className="applications-section" id="applications" aria-labelledby="applications-title">
          <div className="applications-heading">
            <div>
              <div className="section-kicker">Application inventory</div>
              <h2 id="applications-title">Deployments under your control</h2>
              <p>Runtime truth, source revision, routing, and the latest deployment state in one place.</p>
            </div>
            <div className="sync-status">
              <span>Last sync</span>
              <strong>{lastSyncLabel}</strong>
            </div>
          </div>

          <div className="application-toolbar">
            <label className="search-control">
              <Search className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only">Search applications</span>
              <input
                type="search"
                value={searchQuery}
                onChange={(event) => setSearchQuery(event.target.value)}
                placeholder="Search name, repository, or branch"
              />
            </label>
            <div className="filter-list" aria-label="Filter applications">
              {filters.map((filter) => (
                <button
                  type="button"
                  key={filter.id}
                  className={activeFilter === filter.id ? 'is-active' : ''}
                  onClick={() => setActiveFilter(filter.id)}
                >
                  {filter.label}
                </button>
              ))}
            </div>
            <button
              type="button"
              onClick={() => void loadData(true)}
              className="button button--quiet toolbar-refresh"
              disabled={refreshing}
              title="Refresh dashboard"
            >
              <RefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} aria-hidden="true" />
              <span>Refresh</span>
            </button>
          </div>

          {loading && apps.length === 0 ? (
            <div className="loading-panel">
              <span className="loading-panel__mark"><RefreshCw className="h-5 w-5 animate-spin" /></span>
              <strong>Connecting to the microPass engine</strong>
              <p>Reading application definitions and actual container state.</p>
            </div>
          ) : apps.length === 0 ? (
            <div className="empty-state">
              <span className="empty-state__icon"><Container className="h-7 w-7" /></span>
              <div className="section-kicker">Your control plane is ready</div>
              <h3>Register the first application</h3>
              <p>Point microPass at a trusted Git repository and it will build, verify, and run the container locally.</p>
              <button type="button" onClick={() => setShowCreateModal(true)} className="button button--primary">
                <Plus className="h-4 w-4" />
                Register application
              </button>
            </div>
          ) : visibleApps.length === 0 ? (
            <div className="filtered-empty-state">
              <Search className="h-5 w-5" />
              <div><strong>No applications match this view.</strong><p>Change the filter or clear the search query.</p></div>
              <button type="button" onClick={() => { setSearchQuery(''); setActiveFilter('all'); }}>Clear filters</button>
            </div>
          ) : (
            <div className="application-grid">
              {visibleApps.map((app) => (
                <AppCard
                  key={app.id}
                  app={app}
                  onOpenLogs={(deploymentId, appName, status) =>
                    setActiveConsole({ deploymentId, appName, status })
                  }
                  onRefresh={() => void loadData()}
                  onError={setError}
                />
              ))}
            </div>
          )}
        </section>
      </main>

      <footer className="product-footer">
        <span>microPass · trusted repositories · single-host control plane</span>
        <span>Runtime state refreshes automatically every 3 seconds.</span>
      </footer>

      {showCreateModal && (
        <CreateAppModal onClose={() => setShowCreateModal(false)} onCreated={() => void loadData(true)} />
      )}

      {activeConsole && (
        <LogConsoleModal
          deploymentId={activeConsole.deploymentId}
          appName={activeConsole.appName}
          initialStatus={activeConsole.status}
          onClose={() => setActiveConsole(null)}
          onStatusChange={() => void loadData()}
        />
      )}
    </div>
  );
}

export default App;
