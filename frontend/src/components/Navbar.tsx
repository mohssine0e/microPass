import type React from 'react';
import { Box, Plus, Radio } from 'lucide-react';

interface NavbarProps {
  onNewApp: () => void;
  appCount: number;
  engineReachable: boolean;
}

export const Navbar: React.FC<NavbarProps> = ({ onNewApp, appCount, engineReachable }) => {
  return (
    <header className="product-nav">
      <div className="product-nav__inner">
        <div className="brand-lockup" aria-label="microPass deployment control plane">
          <div className="brand-lockup__mark" aria-hidden="true">
            <span>µ</span>P
          </div>
          <div>
            <div className="brand-lockup__name">
              microPass
              <span>local PaaS</span>
            </div>
            <p>Deployment control plane</p>
          </div>
        </div>

        <div className="product-nav__actions">
          <div className={`engine-chip ${engineReachable ? 'is-online' : 'is-degraded'}`}>
            <Radio className="h-3.5 w-3.5" aria-hidden="true" />
            <span>{engineReachable ? 'Engine connected' : 'Engine unavailable'}</span>
          </div>
          <div className="app-count-chip">
            <Box className="h-3.5 w-3.5" aria-hidden="true" />
            <span>{appCount}</span>
            <span className="app-count-chip__label">{appCount === 1 ? 'application' : 'applications'}</span>
          </div>
          <button type="button" onClick={onNewApp} className="button button--primary nav-create-button">
            <Plus className="h-4 w-4" aria-hidden="true" />
            <span>New application</span>
          </button>
        </div>
      </div>
    </header>
  );
};
