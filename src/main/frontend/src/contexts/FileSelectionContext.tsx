import { createContext, useContext, useState } from 'react';

interface FileSelectionCtx {
  selectedPath: string | null;
  selectedType: 'file' | 'directory' | null;
  setSelection: (path: string | null, type: 'file' | 'directory' | null) => void;
  clearSelection: () => void;
}

export const FileSelectionContext = createContext<FileSelectionCtx>({
  selectedPath: null,
  selectedType: null,
  setSelection: () => {},
  clearSelection: () => {},
});

export function useFileSelection() {
  return useContext(FileSelectionContext);
}

export function FileSelectionProvider({ children }: { children: React.ReactNode }) {
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [selectedType, setSelectedType] = useState<'file' | 'directory' | null>(null);

  const setSelection = (path: string | null, type: 'file' | 'directory' | null) => {
    setSelectedPath(path);
    setSelectedType(type);
  };

  const clearSelection = () => {
    setSelectedPath(null);
    setSelectedType(null);
  };

  return (
    <FileSelectionContext.Provider value={{ selectedPath, selectedType, setSelection, clearSelection }}>
      {children}
    </FileSelectionContext.Provider>
  );
}
