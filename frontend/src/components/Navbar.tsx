import React from 'react';
import { Rocket, Plus, Server } from 'lucide-react';

interface NavbarProps {
  onNewApp: () => void;
  appCount: number;
}

export const Navbar: React.FC<NavbarProps> = ({ onNewApp, appCount }) => {
  return (
    <header className="border-b border-slate-800 bg-slate-900/80 backdrop-blur sticky top-0 z-30">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
        <div className="flex items-center space-x-3">
          <div className="h-10 w-10 rounded-xl bg-gradient-to-tr from-cyan-500 to-blue-600 flex items-center justify-center shadow-lg shadow-cyan-500/20">
            <Rocket className="h-5 w-5 text-white" />
          </div>
          <div>
            <h1 className="text-lg font-bold tracking-tight text-white flex items-center gap-2">
              microPass
              <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-cyan-500/10 text-cyan-400 border border-cyan-500/20">
                PaaS
              </span>
            </h1>
            <p className="text-xs text-slate-400">Self-Hosted Application Deployment Engine</p>
          </div>
        </div>

        <div className="flex items-center space-x-4">
          <div className="hidden sm:flex items-center space-x-2 text-xs text-slate-400 bg-slate-800/60 px-3 py-1.5 rounded-lg border border-slate-700/50">
            <Server className="h-3.5 w-3.5 text-emerald-400" />
            <span>{appCount} {appCount === 1 ? 'Application' : 'Applications'}</span>
          </div>

          <button
            onClick={onNewApp}
            className="flex items-center space-x-2 bg-gradient-to-r from-cyan-500 to-blue-600 hover:from-cyan-400 hover:to-blue-500 text-white text-sm font-semibold px-4 py-2 rounded-lg shadow-md shadow-cyan-500/20 transition-all active:scale-95"
          >
            <Plus className="h-4 w-4" />
            <span>New App</span>
          </button>
        </div>
      </div>
    </header>
  );
};

