import type { AppDeps } from './app.js';

export type AuthUser = { id: number; name: string };

export type AppEnv = {
  Variables: {
    deps: AppDeps;
    user: AuthUser;
    token: string;
  };
};
