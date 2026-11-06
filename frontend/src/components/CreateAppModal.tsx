import { useEffect, useRef, useState } from 'react';
import { ArrowRight, Box, Container, GitBranch, GitFork, Info, Loader2, Plus, X } from 'lucide-react';
import { createApp, fetchBranches } from '../api';
import type { CreateAppForm } from '../types';
import { useDialogFocus } from '../dialogFocus';

interface CreateAppModalProps {
  onClose: () => void;
  onCreated: () => void;
}

export function CreateAppModal({ onClose, onCreated }: CreateAppModalProps) {
  const dialogRef = useRef<HTMLElement>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);
  useDialogFocus(dialogRef, nameInputRef);
  const [form, setForm] = useState<CreateAppForm>({
    name: '',
    gitUrl: '',
    defaultBranch: 'main',
    dockerfilePath: 'Dockerfile',
    targetPort: 80,
  });
  const [branches, setBranches] = useState<string[]>([]);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !submitting) onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose, submitting]);

  const handleGitUrlBlur = async () => {
    if (!form.gitUrl.trim()) return;
    setLoadingBranches(true);
    try {
      const detected = await fetchBranches(form.gitUrl);
      setBranches(detected);
      if (detected.length > 0 && !detected.includes(form.defaultBranch)) {
        setForm((previous) => ({ ...previous, defaultBranch: detected[0] }));
      }
    } finally {
      setLoadingBranches(false);
    }
  };

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await createApp(form);
      onCreated();
      onClose();
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : 'Failed to register application');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !submitting) onClose();
      }}
    >
      <section ref={dialogRef} className="modal-shell create-modal" role="dialog" aria-modal="true" aria-labelledby="create-app-title">
        <button type="button" onClick={onClose} className="modal-close" aria-label="Close registration dialog">
          <X className="h-4 w-4" />
        </button>

        <aside className="create-modal__aside">
          <div className="create-modal__mark"><Plus className="h-5 w-5" /></div>
          <div className="section-kicker">New workload</div>
          <h2 id="create-app-title">Register an application source</h2>
          <p>
            microPass stores the desired configuration now. A deployment starts only when you explicitly choose
            <strong> Deploy</strong> from the application card.
          </p>
          <div className="create-flow">
            <div><span>1</span><div><strong>Connect source</strong><p>Choose a trusted repository and branch.</p></div></div>
            <div><span>2</span><div><strong>Define runtime</strong><p>Tell Docker where to build and which port to expose.</p></div></div>
            <div><span>3</span><div><strong>Deploy when ready</strong><p>Watch the full pipeline in the live console.</p></div></div>
          </div>
          <div className="trust-notice">
            <Info className="h-4 w-4" />
            <p>Current security model: register only repositories you trust. Docker builds can execute powerful instructions.</p>
          </div>
        </aside>

        <form onSubmit={handleSubmit} className="create-modal__form">
          <header className="form-header">
            <div>
              <span>Application configuration</span>
              <h3>Source and runtime details</h3>
            </div>
            <small>Fields marked * are required</small>
          </header>

          {error && <div className="form-error" role="alert">{error}</div>}

          <div className="form-section">
            <div className="form-section__title"><GitFork className="h-4 w-4" /><span>Source</span></div>
            <label className="form-field">
              <span>Application name *</span>
              <div className="input-shell">
                <Box className="h-4 w-4" />
                <input
                  ref={nameInputRef}
                  type="text"
                  required
                  placeholder="inventory-api"
                  value={form.name}
                  onChange={(event) =>
                    setForm({ ...form, name: event.target.value.toLowerCase().replace(/[^a-z0-9-_]/g, '') })
                  }
                />
              </div>
              <small>Lowercase letters, numbers, hyphens, and underscores.</small>
            </label>

            <label className="form-field">
              <span>Git repository URL or local path *</span>
              <div className="input-shell">
                <GitFork className="h-4 w-4" />
                <input
                  type="text"
                  required
                  placeholder="https://github.com/team/service.git"
                  value={form.gitUrl}
                  onChange={(event) => setForm({ ...form, gitUrl: event.target.value })}
                  onBlur={() => void handleGitUrlBlur()}
                />
                {loadingBranches && <Loader2 className="h-4 w-4 animate-spin input-spinner" />}
              </div>
              <small>Branch discovery runs automatically when the field loses focus.</small>
            </label>

            <label className="form-field">
              <span className="form-field__label-row">
                Default branch
                {branches.length > 0 && <b>{branches.length} detected</b>}
              </span>
              <div className="input-shell">
                <GitBranch className="h-4 w-4" />
                {branches.length > 0 ? (
                  <select
                    value={form.defaultBranch}
                    onChange={(event) => setForm({ ...form, defaultBranch: event.target.value })}
                  >
                    {branches.map((branch) => <option key={branch} value={branch}>{branch}</option>)}
                  </select>
                ) : (
                  <input
                    type="text"
                    value={form.defaultBranch}
                    onChange={(event) => setForm({ ...form, defaultBranch: event.target.value })}
                    placeholder="main"
                  />
                )}
              </div>
            </label>
          </div>

          <div className="form-section">
            <div className="form-section__title"><Container className="h-4 w-4" /><span>Container runtime</span></div>
            <div className="form-grid">
              <label className="form-field">
                <span>Dockerfile path</span>
                <input
                  className="form-input"
                  type="text"
                  value={form.dockerfilePath}
                  onChange={(event) => setForm({ ...form, dockerfilePath: event.target.value })}
                  placeholder="Dockerfile"
                />
                <small>Relative to the repository root.</small>
              </label>
              <label className="form-field">
                <span>Container port</span>
                <input
                  className="form-input"
                  type="number"
                  min="1"
                  max="65535"
                  value={form.targetPort}
                  onChange={(event) => setForm({ ...form, targetPort: Number.parseInt(event.target.value, 10) || 80 })}
                />
                <small>The port your process listens on.</small>
              </label>
            </div>
          </div>

          <footer className="form-actions">
            <button type="button" onClick={onClose} className="button button--quiet" disabled={submitting}>Cancel</button>
            <button type="submit" className="button button--primary" disabled={submitting}>
              {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
              Register application
              {!submitting && <ArrowRight className="h-4 w-4" />}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}
