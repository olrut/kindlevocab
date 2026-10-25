import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import styles from './Toast.module.css';

const ToastContext = createContext<(message: string) => void>(() => {});

/** `toast("Saved")` shows a short confirmation in the corner. */
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; key: number } | null>(null);
  const show = useCallback((message: string) => setToast({ message, key: Date.now() }), []);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 3000);
    return () => clearTimeout(timer);
  }, [toast]);

  return (
    <ToastContext.Provider value={show}>
      {children}
      {toast && (
        <div role="status" className={styles.toast} key={toast.key}>
          {toast.message}
        </div>
      )}
    </ToastContext.Provider>
  );
}
