import { useNavigate } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Puzzle } from 'lucide-react';

/** Maps lowercase framework name fragments → Badge variant */
type BadgeVariant = 'default' | 'secondary' | 'outline' | 'muted' | 'success' | 'warning' | 'info' | 'purple';

const frameworkVariants: Array<[string, BadgeVariant]> = [
  ['spring', 'success'],
  ['django', 'success'],
  ['rails', 'warning'],
  ['laravel', 'warning'],
  ['nestjs', 'warning'],
  ['express', 'warning'],
  ['fastapi', 'info'],
  ['flask', 'muted'],
  ['react', 'info'],
  ['angular', 'warning'],
  ['vue', 'success'],
  ['kafka', 'purple'],
  ['graphql', 'purple'],
  ['grpc', 'info'],
  ['websocket', 'info'],
  ['neo4j', 'info'],
  ['postgres', 'info'],
  ['mysql', 'warning'],
  ['redis', 'warning'],
  ['mongodb', 'success'],
  ['docker', 'info'],
  ['kubernetes', 'info'],
  ['terraform', 'purple'],
  ['aws', 'warning'],
  ['gcp', 'info'],
  ['azure', 'info'],
];

function getVariant(name: string): BadgeVariant {
  const lower = name.toLowerCase();
  return frameworkVariants.find(([k]) => lower.includes(k))?.[1] ?? 'muted';
}

interface FrameworkBadgesProps {
  frameworks: Record<string, number>;
}

export default function FrameworkBadges({ frameworks }: FrameworkBadgesProps) {
  const navigate = useNavigate();
  const entries = Object.entries(frameworks || {});
  if (entries.length === 0) return null;

  const sorted = entries.sort(([, a], [, b]) => b - a);

  return (
    <Card className="border-border/60 bg-card/80 backdrop-blur-sm">
      <CardHeader className="pb-3 px-5 pt-4">
        <CardTitle className="flex items-center gap-2 text-xs font-medium text-muted-foreground uppercase tracking-widest">
          <Puzzle className="w-3.5 h-3.5" />
          Frameworks &amp; Technologies
        </CardTitle>
      </CardHeader>
      <CardContent className="px-5 pb-4 pt-0">
        <div className="flex flex-wrap gap-1.5" role="list" aria-label="Detected frameworks">
          {sorted.map(([fw, count]) => (
            <Badge
              key={fw}
              variant={getVariant(fw)}
              className="cursor-pointer hover:opacity-80 transition-opacity select-none gap-1.5 px-2.5 py-1"
              onClick={() => navigate(`/explorer?q=${encodeURIComponent(fw)}`)}
              role="listitem"
              title={`${count.toLocaleString()} nodes`}
            >
              {fw}
              <span className="opacity-50 font-mono text-[10px]">{count.toLocaleString()}</span>
            </Badge>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
