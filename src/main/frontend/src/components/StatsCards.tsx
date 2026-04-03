import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, GitBranch, FileCode2, Languages } from 'lucide-react';
import { Card, CardContent } from '@/components/ui/card';
import { cn } from '@/lib/utils';

interface StatsCardsProps {
  totalNodes: number;
  totalEdges: number;
  totalFiles: number;
  totalLanguages: number;
}

function AnimatedCounter({ value, duration = 1200 }: { value: number; duration?: number }) {
  const [display, setDisplay] = useState(0);
  const rafRef = useRef<number>();

  useEffect(() => {
    if (value === 0) { setDisplay(0); return; }
    const start = performance.now();

    const tick = (now: number) => {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      // Ease-out quart
      const eased = 1 - Math.pow(1 - progress, 4);
      setDisplay(Math.round(value * eased));
      if (progress < 1) {
        rafRef.current = requestAnimationFrame(tick);
      }
    };

    rafRef.current = requestAnimationFrame(tick);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
  }, [value, duration]);

  return <>{display.toLocaleString()}</>;
}

interface StatCardConfig {
  key: 'nodes' | 'edges' | 'files' | 'languages';
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  /** tailwind gradient for the number */
  gradient: string;
  /** tailwind gradient for the icon bg */
  iconBg: string;
  /** route to navigate to on click (optional) */
  href?: string;
  description: string;
}

const cardConfigs: StatCardConfig[] = [
  {
    key: 'nodes',
    label: 'Nodes',
    icon: Box,
    gradient: 'from-indigo-400 to-purple-400',
    iconBg: 'bg-indigo-500/10 text-indigo-400',
    href: '/explorer',
    description: 'Total graph nodes',
  },
  {
    key: 'edges',
    label: 'Edges',
    icon: GitBranch,
    gradient: 'from-emerald-400 to-cyan-400',
    iconBg: 'bg-emerald-500/10 text-emerald-400',
    description: 'Relationships',
  },
  {
    key: 'files',
    label: 'Files',
    icon: FileCode2,
    gradient: 'from-amber-400 to-orange-400',
    iconBg: 'bg-amber-500/10 text-amber-400',
    href: '/explorer',
    description: 'Source files scanned',
  },
  {
    key: 'languages',
    label: 'Languages',
    icon: Languages,
    gradient: 'from-rose-400 to-pink-400',
    iconBg: 'bg-rose-500/10 text-rose-400',
    description: 'Detected languages',
  },
];

export default function StatsCards({ totalNodes, totalEdges, totalFiles, totalLanguages }: StatsCardsProps) {
  const navigate = useNavigate();
  const values: Record<string, number> = {
    nodes: totalNodes,
    edges: totalEdges,
    files: totalFiles,
    languages: totalLanguages,
  };

  return (
    <div className="grid grid-cols-2 xl:grid-cols-4 gap-3">
      {cardConfigs.map((cfg, i) => {
        const Icon = cfg.icon;
        const val = values[cfg.key];
        const isClickable = !!cfg.href;

        return (
          <Card
            key={cfg.key}
            onClick={isClickable ? () => navigate(cfg.href!) : undefined}
            className={cn(
              'relative overflow-hidden border-border/60 bg-card/80 backdrop-blur-sm',
              'opacity-0 animate-slide-up',
              `stagger-${i + 1}`,
              isClickable && 'cursor-pointer hover:border-primary/40 hover:shadow-md hover:-translate-y-0.5 transition-all duration-200',
            )}
            style={{ animationFillMode: 'forwards' }}
            role={isClickable ? 'button' : undefined}
            tabIndex={isClickable ? 0 : undefined}
            onKeyDown={isClickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') navigate(cfg.href!); } : undefined}
            aria-label={isClickable ? `View ${cfg.label} in Explorer` : undefined}
          >
            {/* Subtle gradient accent top-right */}
            <div className={cn(
              'absolute top-0 right-0 w-20 h-20 rounded-full blur-2xl opacity-10',
              cfg.iconBg.replace('bg-', 'bg-').replace('/10', '/40')
            )} />

            <CardContent className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-widest mb-1">
                    {cfg.label}
                  </p>
                  <p className={cn(
                    'text-2xl font-bold bg-gradient-to-r bg-clip-text text-transparent tabular-nums',
                    cfg.gradient
                  )}>
                    <AnimatedCounter value={val} />
                  </p>
                  <p className="text-[10px] text-muted-foreground/60 mt-1 hidden sm:block">
                    {cfg.description}
                  </p>
                </div>
                <div className={cn('p-2 rounded-lg shrink-0 mt-0.5', cfg.iconBg)}>
                  <Icon className="w-4 h-4" />
                </div>
              </div>
            </CardContent>
          </Card>
        );
      })}
    </div>
  );
}
