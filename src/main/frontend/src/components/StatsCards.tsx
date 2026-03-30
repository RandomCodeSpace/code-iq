import { useEffect, useRef, useState } from 'react';
import { Box, GitBranch, FileCode2, Languages } from 'lucide-react';

interface StatsCardsProps {
  totalNodes: number;
  totalEdges: number;
  totalFiles: number;
  totalLanguages: number;
}

function AnimatedCounter({ value, duration = 1500 }: { value: number; duration?: number }) {
  const [display, setDisplay] = useState(0);
  const ref = useRef<number>();

  useEffect(() => {
    if (value === 0) { setDisplay(0); return; }
    const start = performance.now();
    const from = 0;

    const tick = (now: number) => {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      // Ease-out cubic
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplay(Math.round(from + (value - from) * eased));
      if (progress < 1) {
        ref.current = requestAnimationFrame(tick);
      }
    };

    ref.current = requestAnimationFrame(tick);
    return () => { if (ref.current) cancelAnimationFrame(ref.current); };
  }, [value, duration]);

  return <>{display.toLocaleString()}</>;
}

const cards = [
  { key: 'nodes', label: 'Nodes', icon: Box, color: 'from-brand-500 to-purple-500', bgGlow: 'brand' },
  { key: 'edges', label: 'Edges', icon: GitBranch, color: 'from-emerald-500 to-cyan-500', bgGlow: 'emerald' },
  { key: 'files', label: 'Files', icon: FileCode2, color: 'from-amber-500 to-orange-500', bgGlow: 'amber' },
  { key: 'languages', label: 'Languages', icon: Languages, color: 'from-rose-500 to-pink-500', bgGlow: 'rose' },
] as const;

export default function StatsCards({ totalNodes, totalEdges, totalFiles, totalLanguages }: StatsCardsProps) {
  const values: Record<string, number> = {
    nodes: totalNodes,
    edges: totalEdges,
    files: totalFiles,
    languages: totalLanguages,
  };

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
      {cards.map((card, i) => {
        const Icon = card.icon;
        const val = values[card.key];
        return (
          <div
            key={card.key}
            className={`glass-card-hover p-5 animate-slide-up stagger-${i + 1} opacity-0`}
            style={{ animationFillMode: 'forwards' }}
          >
            <div className="flex items-start justify-between">
              <div>
                <p className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-1">
                  {card.label}
                </p>
                <p className={`text-3xl font-bold bg-gradient-to-r ${card.color} bg-clip-text text-transparent`}>
                  <AnimatedCounter value={val} />
                </p>
              </div>
              <div className={`p-2.5 rounded-lg bg-gradient-to-br ${card.color} bg-opacity-10`}>
                <Icon className="w-5 h-5 text-white/80" />
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
