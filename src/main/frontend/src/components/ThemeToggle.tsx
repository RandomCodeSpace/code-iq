import { Sun, Moon, Monitor } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';

export default function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  const cycle = () => {
    const next = theme === 'dark' ? 'light' : theme === 'light' ? 'system' : 'dark';
    setTheme(next);
  };

  return (
    <button
      onClick={cycle}
      className="flex items-center gap-2 px-3 py-2 rounded-lg
                 text-surface-400 hover:text-surface-100 hover:bg-surface-800/50
                 transition-all duration-200"
      title={`Theme: ${theme}`}
    >
      {theme === 'dark' && <Moon className="w-4 h-4" />}
      {theme === 'light' && <Sun className="w-4 h-4" />}
      {theme === 'system' && <Monitor className="w-4 h-4" />}
      <span className="text-xs font-medium capitalize hidden lg:inline">{theme}</span>
    </button>
  );
}
