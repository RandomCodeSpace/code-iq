import * as React from "react";
import { cn } from "@/lib/utils";

export interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {}

const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          "flex h-8 w-full rounded bg-surface-800 border border-surface-700/50 px-3 py-1 text-xs font-mono text-surface-200 shadow-sm transition-colors placeholder:text-surface-600 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-brand-500/50 focus-visible:border-brand-500/50 disabled:cursor-not-allowed disabled:opacity-50",
          className
        )}
        ref={ref}
        {...props}
      />
    );
  }
);
Input.displayName = "Input";

export { Input };
