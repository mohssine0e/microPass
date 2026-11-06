import { useEffect, useRef, useState } from 'react';
import { ArrowDown, Ban, Check, Copy, Loader2, Radio, Terminal, X } from 'lucide-react';
import { cancelDeployment, getStreamUrl } from '../api';
import type { DeploymentStatus } from '../types';
import { getStatusLabel, getStatusTone } from '../status';
import { useDialogFocus } from '../dialogFocus';

interface LogConsoleModalProps {
  deploymentId: number;
  appName: string;
  initialStatus: DeploymentStatus;
  onClose: () => void;
  onStatusChange?: (status: DeploymentStatus) => void;
}

export function LogConsoleModal({
  deploymentId,
  appName,
  initialStatus,
  onClose,
  onStatusChange,
}: LogConsoleModalProps) {
  const [logs, setLogs] = useState<string[]>([]);
  const [status, setStatus] = useState<DeploymentStatus>(initialStatus);
  const [copied, setCopied] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const terminalRef = useRef<HTMLDivElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  useDialogFocus(dialogRef, closeButtonRef);

  const isTerminalState = ['HEALTHY', 'FAILED', 'CANCELLED', 'STOPPED'].includes(status);
  const statusTone = getStatusTone(status, status === 'HEALTHY');

  useEffect(() => {
    setStatus(initialStatus);
  }, [initialStatus]);

  useEffect(() => {
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose]);

  useEffect(() => {
    const eventSource = new EventSource(getStreamUrl(deploymentId));

    eventSource.onmessage = (event) => {
      const line = event.data;
      setLogs((previous) => [...previous, line]);

      if (line.includes('Deployment SUCCESS') || line.includes('Readiness probe PASSED')) {
        setStatus('HEALTHY');
        onStatusChange?.('HEALTHY');
      } else if (line.includes('Deployment stopped and cancelled') || line.includes('[CANCELLED]')) {
        setStatus('CANCELLED');
        onStatusChange?.('CANCELLED');
      } else if (line.includes('[ERROR]') || line.includes('Deployment FAILED')) {
        setStatus('FAILED');
        onStatusChange?.('FAILED');
      }
    };

    const handleComplete = (event: Event) => {
      const finalStatus = (event as MessageEvent<string>).data as DeploymentStatus | 'TERMINAL';
      if (finalStatus !== 'TERMINAL') {
        setStatus(finalStatus);
        onStatusChange?.(finalStatus);
      }
      eventSource.close();
    };

    eventSource.addEventListener('complete', handleComplete);
    eventSource.onerror = () => {
      // EventSource retries transient failures until the named completion event closes it.
    };

    return () => {
      eventSource.removeEventListener('complete', handleComplete);
      eventSource.close();
    };
  }, [deploymentId, onStatusChange]);

  useEffect(() => {
    if (autoScroll && terminalRef.current) {
      terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
    }
  }, [autoScroll, logs]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(logs.join('\n'));
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      setError('The browser could not copy the console output.');
    }
  };

  const handleCancel = async () => {
    setError(null);
    setCancelling(true);
    try {
      await cancelDeployment(deploymentId);
      setStatus('CANCELLED');
      onStatusChange?.('CANCELLED');
    } catch (caught: unknown) {
      setError(caught instanceof Error ? caught.message : 'Failed to cancel deployment');
    } finally {
      setCancelling(false);
    }
  };

  const jumpToLatest = () => {
    setAutoScroll(true);
    if (terminalRef.current) terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
  };

  return (
    <div className="modal-backdrop console-backdrop" role="presentation">
      <section ref={dialogRef} className="modal-shell console-modal" role="dialog" aria-modal="true" aria-labelledby="console-title">
        <header className="console-header">
          <div className="console-title">
            <span className="console-title__mark"><Terminal className="h-5 w-5" /></span>
            <div>
              <span>Live deployment output</span>
              <h2 id="console-title">{appName}</h2>
            </div>
            <code>run #{deploymentId}</code>
          </div>
          <div className="console-header__actions">
            <span className={`status-pill status-pill--${statusTone}`}>
              {!isTerminalState && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              {isTerminalState && <i />}
              {getStatusLabel(status, status === 'HEALTHY')}
            </span>
            <button type="button" onClick={() => void handleCopy()} className="modal-icon-button" title="Copy all output">
              {copied ? <Check className="h-4 w-4 text-emerald-300" /> : <Copy className="h-4 w-4" />}
            </button>
            <button ref={closeButtonRef} type="button" onClick={onClose} className="modal-icon-button" aria-label="Close console">
              <X className="h-4 w-4" />
            </button>
          </div>
        </header>

        <div className="console-toolbar">
          <div>
            <span className={`console-stream-state ${isTerminalState ? 'is-complete' : ''}`}><i />
              {isTerminalState ? 'Stream complete' : 'Streaming stdout and stderr'}
            </span>
            <span>{logs.length} {logs.length === 1 ? 'line' : 'lines'}</span>
          </div>
          <button type="button" onClick={jumpToLatest} className={autoScroll ? 'is-active' : ''}>
            <ArrowDown className="h-3.5 w-3.5" /> Follow latest
          </button>
        </div>

        <div
          ref={terminalRef}
          onScroll={(event) => {
            const target = event.currentTarget;
            setAutoScroll(target.scrollHeight - target.scrollTop <= target.clientHeight + 50);
          }}
          className="deployment-console"
          aria-live="polite"
        >
          {logs.length === 0 ? (
            <div className="console-waiting">
              <span><Radio className="h-5 w-5" /></span>
              <strong>Connecting to the deployment stream</strong>
              <p>Historical output is replayed first, then new lines arrive live.</p>
            </div>
          ) : (
            logs.map((line, index) => {
              const normalized = line.toLowerCase();
              const isError = line.includes('[ERROR]') || normalized.includes('failed') || normalized.includes('fatal');
              const isSuccess = line.includes('SUCCESS') || line.includes('PASSED') || normalized.includes('complete');
              const isInfo = line.startsWith('>>>') || line.startsWith('===');
              const isCancel = line.includes('[CANCEL]');
              const tone = isError ? 'error' : isSuccess ? 'success' : isCancel ? 'warning' : isInfo ? 'info' : 'default';
              return (
                <div key={`${index}-${line.slice(0, 12)}`} className={`console-line console-line--${tone}`}>
                  <span>{String(index + 1).padStart(3, '0')}</span>
                  <code>{line}</code>
                </div>
              );
            })
          )}
        </div>

        {error && <div className="console-error" role="alert">{error}</div>}

        <footer className="console-footer">
          <div>
            <strong>{isTerminalState ? 'Deployment process finished' : 'The pipeline continues if you close this window'}</strong>
            <span>{isTerminalState ? 'Review the output, then return to the dashboard.' : 'Closing the console does not cancel work.'}</span>
          </div>
          <div>
            {!isTerminalState && (
              <button type="button" onClick={() => void handleCancel()} disabled={cancelling} className="button button--danger">
                {cancelling ? <Loader2 className="h-4 w-4 animate-spin" /> : <Ban className="h-4 w-4" />}
                Cancel deployment
              </button>
            )}
            <button type="button" onClick={onClose} className="button button--secondary">
              {isTerminalState ? 'Close console' : 'Close and keep running'}
            </button>
          </div>
        </footer>
      </section>
    </div>
  );
}
