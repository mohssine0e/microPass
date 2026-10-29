import React, { useEffect, useRef, useState } from 'react';
import { X, Terminal, Copy, Check, Ban, Loader2 } from 'lucide-react';
import { cancelDeployment, getStreamUrl } from '../api';
import { DeploymentStatus } from '../types';

interface LogConsoleModalProps {
  deploymentId: number;
  appName: string;
  initialStatus: DeploymentStatus;
  onClose: () => void;
  onStatusChange?: (status: DeploymentStatus) => void;
}

export const LogConsoleModal: React.FC<LogConsoleModalProps> = ({
  deploymentId,
  appName,
  initialStatus,
  onClose,
  onStatusChange,
}) => {
  const [logs, setLogs] = useState<string[]>([]);
  const [status, setStatus] = useState<DeploymentStatus>(initialStatus);
  const [copied, setCopied] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const terminalRef = useRef<HTMLDivElement>(null);
  const eventSourceRef = useRef<EventSource | null>(null);

  const isTerminalState =
    status === 'HEALTHY' ||
    status === 'FAILED' ||
    status === 'CANCELLED' ||
    status === 'STOPPED';

  useEffect(() => {
    setStatus(initialStatus);
  }, [initialStatus]);

  useEffect(() => {
    const streamUrl = getStreamUrl(deploymentId);
    const es = new EventSource(streamUrl);
    eventSourceRef.current = es;

    es.onopen = () => {
      setLogs((prev) => (prev.length === 0 ? ['>>> Connected to deployment live stream...'] : prev));
    };

    es.onmessage = (event) => {
      const line = event.data;
      setLogs((prev) => [...prev, line]);

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

    es.onerror = () => {
      // EventSource automatically retries unless closed
    };

    return () => {
      es.close();
    };
  }, [deploymentId, onStatusChange]);

  useEffect(() => {
    if (autoScroll && terminalRef.current) {
      terminalRef.current.scrollTop = terminalRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const handleCopy = () => {
    navigator.clipboard.writeText(logs.join('\n'));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleCancel = async () => {
    setCancelling(true);
    try {
      await cancelDeployment(deploymentId);
      setStatus('CANCELLED');
      onStatusChange?.('CANCELLED');
    } catch (e) {
      console.error('Cancel failed', e);
    } finally {
      setCancelling(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-950/80 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="bg-slate-900 border border-slate-700/80 rounded-2xl w-full max-w-4xl max-h-[88vh] flex flex-col shadow-2xl overflow-hidden">
        {/* Modal Header */}
        <div className="px-5 py-4 border-b border-slate-800 flex items-center justify-between bg-slate-900/60">
          <div className="flex items-center space-x-3">
            <div className="p-2 rounded-lg bg-slate-800 text-cyan-400 border border-slate-700">
              <Terminal className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-semibold text-white tracking-wide">Deployment Console</h3>
                <span className="text-xs px-2 py-0.5 rounded-full bg-slate-800 text-slate-300 font-mono border border-slate-700">
                  #{deploymentId}
                </span>
                <span className={`text-xs px-2.5 py-0.5 rounded-full font-medium ${
                  status === 'HEALTHY' ? 'bg-emerald-500/10 text-emerald-400 border border-emerald-500/20' :
                  status === 'FAILED' ? 'bg-rose-500/10 text-rose-400 border border-rose-500/20' :
                  status === 'CANCELLED' ? 'bg-amber-500/10 text-amber-400 border border-amber-500/20' :
                  'bg-blue-500/10 text-blue-400 border border-blue-500/20 animate-pulse'
                }`}>
                  {status}
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">Application: <span className="text-slate-200 font-medium">{appName}</span></p>
            </div>
          </div>

          <div className="flex items-center space-x-2">
            <button
              onClick={handleCopy}
              className="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition"
              title="Copy all logs"
            >
              {copied ? <Check className="h-4 w-4 text-emerald-400" /> : <Copy className="h-4 w-4" />}
            </button>
            <button
              onClick={onClose}
              className="text-slate-400 hover:text-white p-2 rounded-lg hover:bg-slate-800 transition"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        {/* Terminal Body */}
        <div
          ref={terminalRef}
          onScroll={(e) => {
            const target = e.currentTarget;
            const atBottom = target.scrollHeight - target.scrollTop <= target.clientHeight + 50;
            setAutoScroll(atBottom);
          }}
          className="flex-1 p-4 bg-black/90 font-mono text-xs overflow-y-auto leading-relaxed select-text space-y-1 min-h-[380px]"
        >
          {logs.length === 0 ? (
            <div className="flex items-center justify-center h-full text-slate-500 space-x-2 py-12">
              <Loader2 className="h-4 w-4 animate-spin text-cyan-400" />
              <span>Connecting to live build stream...</span>
            </div>
          ) : (
            logs.map((line, idx) => {
              const isError = line.includes('[ERROR]') || line.includes('failed') || line.includes('Fatal');
              const isSuccess = line.includes('SUCCESS') || line.includes('PASSED') || line.includes('complete');
              const isInfo = line.startsWith('>>>') || line.startsWith('===');
              const isCancel = line.includes('[CANCEL]');

              let color = 'text-slate-300';
              if (isError) color = 'text-rose-400';
              else if (isSuccess) color = 'text-emerald-400 font-semibold';
              else if (isCancel) color = 'text-amber-400 font-semibold';
              else if (isInfo) color = 'text-cyan-400 font-medium';

              return (
                <div key={idx} className={`whitespace-pre-wrap break-all ${color}`}>
                  {line}
                </div>
              );
            })
          )}
        </div>

        {/* Modal Footer */}
        <div className="px-5 py-3 border-t border-slate-800 bg-slate-900/90 flex items-center justify-between text-xs">
          <div className="flex items-center space-x-2 text-slate-400">
            <span className={`h-2 w-2 rounded-full ${isTerminalState ? 'bg-slate-500' : 'bg-emerald-400 animate-pulse'}`} />
            <span>{isTerminalState ? 'Process finished' : 'Streaming live stdout / stderr'}</span>
          </div>

          <div className="flex items-center space-x-3">
            {!isTerminalState && (
              <button
                onClick={handleCancel}
                disabled={cancelling}
                className="flex items-center space-x-1.5 px-3.5 py-1.5 rounded-lg bg-rose-500/10 hover:bg-rose-500/20 text-rose-400 border border-rose-500/30 font-medium transition disabled:opacity-50"
              >
                {cancelling ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Ban className="h-3.5 w-3.5" />}
                <span>Cancel Build</span>
              </button>
            )}

            <button
              onClick={onClose}
              className="px-4 py-1.5 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-200 border border-slate-700 font-medium transition"
            >
              {isTerminalState ? 'Close Console' : 'Close Console (Keeps running)'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

