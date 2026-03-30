import { useEffect, useRef, useState, useCallback } from 'react';
import { api } from '@/lib/api';
import type { FlowDiagram } from '@/types/api';
import { Maximize2, ZoomIn, ZoomOut, RefreshCw, AlertCircle } from 'lucide-react';
import cytoscape from 'cytoscape';
import dagre from 'cytoscape-dagre';

cytoscape.use(dagre);

const views = ['overview', 'ci', 'deploy', 'runtime', 'auth'];
const viewLabels: Record<string, string> = {
  overview: 'Overview',
  ci: 'CI/CD',
  deploy: 'Deploy',
  runtime: 'Runtime',
  auth: 'Auth',
};

const typeColors: Record<string, string> = {
  process: '#6366f1',
  service: '#8b5cf6',
  database: '#f59e0b',
  queue: '#10b981',
  gateway: '#3b82f6',
  user: '#64748b',
  external: '#94a3b8',
  default: '#6366f1',
};

export default function FlowView() {
  const [activeView, setActiveView] = useState('overview');
  const [diagram, setDiagram] = useState<FlowDiagram | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const cyRef = useRef<cytoscape.Core | null>(null);

  const loadView = useCallback(async (view: string) => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.getFlow(view);
      setDiagram(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setDiagram(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadView(activeView);
  }, [activeView, loadView]);

  useEffect(() => {
    if (!diagram || !containerRef.current) return;
    if (cyRef.current) cyRef.current.destroy();

    const elements: cytoscape.ElementDefinition[] = [];

    for (const node of diagram.nodes || []) {
      elements.push({
        data: {
          id: node.id,
          label: node.label,
          type: node.type || 'default',
          group: node.group,
        },
      });
    }

    for (const edge of diagram.edges || []) {
      elements.push({
        data: {
          source: edge.source,
          target: edge.target,
          label: edge.label || '',
        },
      });
    }

    if (elements.length === 0) return;

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style: [
        {
          selector: 'node',
          style: {
            label: 'data(label)',
            'text-valign': 'center',
            'text-halign': 'center',
            color: '#e2e8f0',
            'font-size': '11px',
            'font-family': 'Inter, system-ui, sans-serif',
            'text-wrap': 'wrap',
            'text-max-width': '90px',
            width: 55,
            height: 55,
            'background-opacity': 0.85,
            'border-width': 2,
            'border-opacity': 0.5,
            shape: 'round-rectangle',
          },
        },
        ...Object.entries(typeColors).map(([type, color]) => ({
          selector: `node[type="${type}"]`,
          style: {
            'background-color': color,
            'border-color': color,
          },
        })),
        {
          selector: 'edge',
          style: {
            width: 1.5,
            'line-color': '#475569',
            'target-arrow-color': '#475569',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            label: 'data(label)',
            'font-size': '9px',
            color: '#64748b',
            'text-rotation': 'autorotate',
            'text-margin-y': -8,
          },
        },
      ],
      layout: {
        name: 'dagre',
        rankDir: 'LR',
        padding: 40,
        spacingFactor: 1.3,
      } as cytoscape.LayoutOptions,
      minZoom: 0.2,
      maxZoom: 4,
      wheelSensitivity: 0.3,
    });

    cyRef.current = cy;
    return () => { cy.destroy(); };
  }, [diagram]);

  const fit = () => cyRef.current?.fit(undefined, 40);
  const zoomIn = () => {
    const cy = cyRef.current;
    if (cy) cy.zoom({ level: cy.zoom() * 1.3, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };
  const zoomOut = () => {
    const cy = cyRef.current;
    if (cy) cy.zoom({ level: cy.zoom() / 1.3, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold gradient-text">Flow Diagrams</h1>
          <p className="text-sm text-surface-400 mt-1">Architecture flow visualization</p>
        </div>
      </div>

      {/* View tabs */}
      <div className="flex gap-1 p-1 bg-surface-900/60 rounded-lg border border-surface-800/50 w-fit">
        {views.map(v => (
          <button
            key={v}
            onClick={() => setActiveView(v)}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-all duration-200 ${
              activeView === v
                ? 'bg-brand-500/15 text-brand-400 border border-brand-500/20'
                : 'text-surface-400 hover:text-surface-200 hover:bg-surface-800/50 border border-transparent'
            }`}
          >
            {viewLabels[v]}
          </button>
        ))}
      </div>

      {/* Graph */}
      <div className="relative glass-card overflow-hidden" style={{ height: 'calc(100vh - 240px)' }}>
        {loading && (
          <div className="absolute inset-0 flex items-center justify-center bg-surface-900/50 z-10">
            <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
          </div>
        )}

        {error && (
          <div className="absolute inset-0 flex items-center justify-center z-10">
            <div className="text-center space-y-3">
              <AlertCircle className="w-10 h-10 text-amber-400 mx-auto" />
              <p className="text-surface-400 text-sm max-w-xs">{error}</p>
              <button onClick={() => loadView(activeView)} className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg bg-brand-500/10 text-brand-400 border border-brand-500/20 text-xs">
                <RefreshCw className="w-3 h-3" /> Retry
              </button>
            </div>
          </div>
        )}

        <div ref={containerRef} className="w-full h-full" />

        <div className="absolute top-4 right-4 flex flex-col gap-1">
          {[
            { icon: ZoomIn, action: zoomIn, label: 'Zoom in' },
            { icon: ZoomOut, action: zoomOut, label: 'Zoom out' },
            { icon: Maximize2, action: fit, label: 'Fit' },
          ].map(({ icon: Icon, action, label }) => (
            <button
              key={label}
              onClick={action}
              title={label}
              className="p-2 rounded-lg bg-surface-900/80 border border-surface-700/50 text-surface-400 hover:text-surface-200 hover:bg-surface-800 transition-colors"
            >
              <Icon className="w-4 h-4" />
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
