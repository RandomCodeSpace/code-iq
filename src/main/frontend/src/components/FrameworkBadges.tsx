const frameworkColors: Record<string, string> = {
  spring: 'bg-green-500/10 text-green-400 border-green-500/20',
  'spring boot': 'bg-green-500/10 text-green-400 border-green-500/20',
  nestjs: 'bg-red-500/10 text-red-400 border-red-500/20',
  express: 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20',
  fastapi: 'bg-teal-500/10 text-teal-400 border-teal-500/20',
  django: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
  react: 'bg-cyan-500/10 text-cyan-400 border-cyan-500/20',
  angular: 'bg-red-500/10 text-red-400 border-red-500/20',
  vue: 'bg-green-500/10 text-green-400 border-green-500/20',
  flask: 'bg-slate-400/10 text-slate-300 border-slate-400/20',
  rails: 'bg-red-500/10 text-red-400 border-red-500/20',
  laravel: 'bg-orange-500/10 text-orange-400 border-orange-500/20',
  kafka: 'bg-purple-500/10 text-purple-400 border-purple-500/20',
  graphql: 'bg-pink-500/10 text-pink-400 border-pink-500/20',
  grpc: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  websocket: 'bg-indigo-500/10 text-indigo-400 border-indigo-500/20',
  neo4j: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  postgres: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  mysql: 'bg-orange-500/10 text-orange-400 border-orange-500/20',
  redis: 'bg-red-500/10 text-red-400 border-red-500/20',
  mongodb: 'bg-green-500/10 text-green-400 border-green-500/20',
  docker: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  kubernetes: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  terraform: 'bg-purple-500/10 text-purple-400 border-purple-500/20',
  aws: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
  gcp: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  azure: 'bg-blue-500/10 text-blue-400 border-blue-500/20',
};

const defaultColor = 'bg-surface-700/30 text-surface-300 border-surface-600/30';

interface FrameworkBadgesProps {
  frameworks: string[];
}

export default function FrameworkBadges({ frameworks }: FrameworkBadgesProps) {
  if (!frameworks || frameworks.length === 0) return null;

  return (
    <div className="glass-card p-5">
      <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider mb-3">
        Frameworks & Technologies
      </h3>
      <div className="flex flex-wrap gap-2">
        {frameworks.map(fw => {
          const lower = fw.toLowerCase();
          const color = Object.entries(frameworkColors).find(([k]) => lower.includes(k))?.[1] || defaultColor;
          return (
            <span
              key={fw}
              className={`inline-flex items-center px-3 py-1.5 rounded-full text-xs font-medium border ${color} transition-all hover:scale-105`}
            >
              {fw}
            </span>
          );
        })}
      </div>
    </div>
  );
}
