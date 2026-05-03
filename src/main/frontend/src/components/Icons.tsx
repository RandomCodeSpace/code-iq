import type { CSSProperties } from 'react';

const base: CSSProperties = { display: 'inline-block', verticalAlign: '-2px' };

function Svg({ d, size = 14 }: { d: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={base}>
      <path d={d} />
    </svg>
  );
}

export const Icon = {
  Nodes: () => <Svg d="M5 12h14M12 5v14M6 6l12 12M6 18l12-12" />,
  Branches: () => <Svg d="M6 3v12a3 3 0 0 0 3 3h6M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z" />,
  File: () => <Svg d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6" />,
  Code: () => <Svg d="M16 18l6-6-6-6M8 6l-6 6 6 6" />,
  Api: () => <Svg d="M2 12h6M16 12h6M12 2v6M12 16v6M5 5l4.5 4.5M14.5 14.5L19 19M5 19l4.5-4.5M14.5 9.5L19 5" />,
  Safety: () => <Svg d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />,
  Appstore: () => <Svg d="M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z" />,
  Build: () => <Svg d="M14.7 6.3a4 4 0 0 0-5.4 5.4l-7 7 2 2 7-7a4 4 0 0 0 5.4-5.4l-3 3-1.5-1.5z" />,
  Play: () => <Svg d="M5 3l14 9-14 9V3z" />,
  Clock: () => <Svg d="M12 6v6l4 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z" />,
  Search: () => <Svg d="M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35" />,
  History: () => <Svg d="M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2" />,
};
