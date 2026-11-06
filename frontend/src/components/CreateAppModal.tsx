import React, { useState } from 'react';
import { X, GitBranch, Loader2, PlusCircle } from 'lucide-react';
import { createApp, fetchBranches } from '../api';
import { CreateAppForm } from '../types';

interface CreateAppModalProps {
  onClose: () => void;
  onCreated: () => void;
}

export const CreateAppModal: React.FC<CreateAppModalProps> = ({ onClose, onCreated }) => {
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

  const handleGitUrlBlur = async () => {
    if (!form.gitUrl.trim()) return;
    setLoadingBranches(true);
    try {
      const detected = await fetchBranches(form.gitUrl);
      if (detected.length > 0) {
        setBranches(detected);
        if (!detected.includes(form.defaultBranch)) {
          setForm((prev) => ({ ...prev, defaultBranch: detected[0] }));
        }
      }
    } catch {
      // Ignore branch auto-detect failure
    } finally {
      setLoadingBranches(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await createApp(form);
      onCreated();
      onClose();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to register project');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-150">
      <div className="bg-slate-900 border border-slate-700/80 rounded-2xl w-full max-w-lg shadow-2xl overflow-hidden">
        <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/60">
          <div className="flex items-center space-x-2.5">
            <PlusCircle className="h-5 w-5 text-cyan-400" />
            <h3 className="font-semibold text-white tracking-wide">Register New Application</h3>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-white p-1 rounded-lg hover:bg-slate-800 transition"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {error && (
            <div className="p-3 rounded-lg bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs">
              {error}
            </div>
          )}

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1.5">
              App Name <span className="text-cyan-400">*</span>
            </label>
            <input
              type="text"
              required
              placeholder="e.g. petclinic or my-api"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value.toLowerCase().replace(/[^a-z0-9-_]/g, '') })}
              className="w-full bg-slate-950 border border-slate-700/80 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500 focus:ring-1 focus:ring-cyan-500 transition"
            />
            <p className="text-[11px] text-slate-500 mt-1">Lowercase alphanumeric, hyphens, and underscores only.</p>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1.5">
              Git Repository URL or Local Path <span className="text-cyan-400">*</span>
            </label>
            <input
              type="text"
              required
              placeholder="https://github.com/user/repo.git or /home/.../path"
              value={form.gitUrl}
              onChange={(e) => setForm({ ...form, gitUrl: e.target.value })}
              onBlur={handleGitUrlBlur}
              className="w-full bg-slate-950 border border-slate-700/80 rounded-lg px-3 py-2 text-sm text-slate-100 placeholder-slate-500 focus:outline-none focus:border-cyan-500 focus:ring-1 focus:ring-cyan-500 transition"
            />
            <p className="text-[11px] text-slate-500 mt-1">Accepts Git HTTP(S) URLs or absolute local directories.</p>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center justify-between">
                <span>Default Branch</span>
                {loadingBranches && <Loader2 className="h-3 w-3 animate-spin text-cyan-400" />}
              </label>
              {branches.length > 0 ? (
                <select
                  value={form.defaultBranch}
                  onChange={(e) => setForm({ ...form, defaultBranch: e.target.value })}
                  className="w-full bg-slate-950 border border-slate-700/80 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500 transition"
                >
                  {branches.map((b) => (
                    <option key={b} value={b}>
                      {b}
                    </option>
                  ))}
                </select>
              ) : (
                <div className="relative">
                  <input
                    type="text"
                    value={form.defaultBranch}
                    onChange={(e) => setForm({ ...form, defaultBranch: e.target.value })}
                    placeholder="main"
                    className="w-full bg-slate-950 border border-slate-700/80 rounded-lg pl-8 pr-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500 transition"
                  />
                  <GitBranch className="h-3.5 w-3.5 text-slate-500 absolute left-2.5 top-3" />
                </div>
              )}
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-300 mb-1.5">Internal Port</label>
              <input
                type="number"
                min="1"
                max="65535"
                value={form.targetPort}
                onChange={(e) => setForm({ ...form, targetPort: parseInt(e.target.value, 10) || 80 })}
                placeholder="80 or 8080"
                className="w-full bg-slate-950 border border-slate-700/80 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500 transition"
              />
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-slate-300 mb-1.5">Dockerfile Relative Path</label>
            <input
              type="text"
              value={form.dockerfilePath}
              onChange={(e) => setForm({ ...form, dockerfilePath: e.target.value })}
              placeholder="Dockerfile"
              className="w-full bg-slate-950 border border-slate-700/80 rounded-lg px-3 py-2 text-sm text-slate-100 focus:outline-none focus:border-cyan-500 transition"
            />
          </div>

          <div className="pt-2 flex items-center justify-end space-x-3">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 text-sm font-medium transition"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="flex items-center space-x-2 px-5 py-2 rounded-lg bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-sm font-semibold shadow-md shadow-cyan-500/20 transition disabled:opacity-50"
            >
              {submitting && <Loader2 className="h-4 w-4 animate-spin" />}
              <span>Register App</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

