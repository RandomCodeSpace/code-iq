import { useEffect, useState } from 'react';
import { X, FileCode2, MapPin, Layers, Tag } from 'lucide-react';
import { api } from '@/lib/api';
import type { NodeResponse } from '@/types/api';

interface NodeDetailModalProps {
  nodeId: string | null;
  onClose: () => void;
}

export default function NodeDetailModal({ nodeId, onClose }: NodeDetailModalProps) {
  const [node, setNode] = useState<NodeResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sourceCode, setSourceCode] = useState<string | null>(null);

  useEffect(() => {
    if (!nodeId) return;
    setLoading(true);
    setError(null);
    setSourceCode(null);

    api.getNodeDetail(nodeId)
      .then(data => {
        setNode(data);
        if (data.file_path && data.line_start && data.line_end) {
          const start = Math.max(1, data.line_start - 3);
          const end = data.line_end + 3;
          return api.readFile(data.file_path, start, end).then(setSourceCode);
        }
      })
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [nodeId]);

  if (!nodeId) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/70 backdrop-blur-sm" onClick={onClose} />
      <div className="relative w-full max-w-2xl max-h-[85vh] overflow-hidden glass-card animate-slide-up">
        {/* Header */}
        <div className="flex items-center justify-between p-5 border-b border-surface-800/50">
          <h2 className="text-lg font-semibold text-surface-100 truncate pr-4">
            {node?.label || nodeId}
          </h2>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/50 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="overflow-y-auto p-5 space-y-5 max-h-[calc(85vh-4rem)]">
          {loading && (
            <div className="flex items-center justify-center py-12">
              <div className="w-6 h-6 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
            </div>
          )}

          {error && (
            <div className="text-red-400 text-sm bg-red-500/10 border border-red-500/20 rounded-lg p-4">
              {error}
            </div>
          )}

          {node && !loading && (
            <>
              {/* Meta badges */}
              <div className="flex flex-wrap gap-2">
                <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-brand-500/10 text-brand-400 border border-brand-500/20">
                  <Tag className="w-3 h-3" />
                  {node.kind}
                </span>
                {node.layer && (
                  <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                    <Layers className="w-3 h-3" />
                    {node.layer}
                  </span>
                )}
                {node.file_path && (
                  <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-surface-700/30 text-surface-300 border border-surface-600/30">
                    <FileCode2 className="w-3 h-3" />
                    {node.file_path}
                  </span>
                )}
                {node.line_start && (
                  <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-medium bg-surface-700/30 text-surface-300 border border-surface-600/30">
                    <MapPin className="w-3 h-3" />
                    L{node.line_start}{node.line_end ? `-${node.line_end}` : ''}
                  </span>
                )}
              </div>

              {/* FQN */}
              {node.fqn && (
                <div>
                  <p className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-1">Fully Qualified Name</p>
                  <code className="text-sm font-mono text-surface-200 bg-surface-800/50 px-3 py-1.5 rounded block break-all">
                    {node.fqn}
                  </code>
                </div>
              )}

              {/* Properties */}
              {node.properties && Object.keys(node.properties).length > 0 && (
                <div>
                  <p className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-2">Properties</p>
                  <div className="grid grid-cols-1 gap-1.5">
                    {Object.entries(node.properties).map(([k, v]) => (
                      <div key={k} className="flex gap-2 text-sm bg-surface-800/30 rounded px-3 py-1.5">
                        <span className="text-surface-400 font-mono flex-shrink-0">{k}:</span>
                        <span className="text-surface-200 font-mono break-all">
                          {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Annotations */}
              {node.annotations && node.annotations.length > 0 && (
                <div>
                  <p className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-2">Annotations</p>
                  <div className="flex flex-wrap gap-1.5">
                    {node.annotations.map(a => (
                      <code key={a} className="text-xs font-mono text-amber-400 bg-amber-500/10 border border-amber-500/20 px-2 py-1 rounded">
                        @{a}
                      </code>
                    ))}
                  </div>
                </div>
              )}

              {/* Source code */}
              {sourceCode && (
                <div>
                  <p className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-2">Source</p>
                  <pre className="text-xs font-mono text-surface-300 bg-surface-900 border border-surface-800/50 rounded-lg p-4 overflow-x-auto max-h-60 overflow-y-auto">
                    {sourceCode}
                  </pre>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
