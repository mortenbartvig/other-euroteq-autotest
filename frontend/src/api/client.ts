import axios from 'axios';

// ---------------------------------------------------------------------------
// Axios instance
// ---------------------------------------------------------------------------

export const api = axios.create({
  baseURL: '',
  withCredentials: true,
  headers: { 'Content-Type': 'application/json' },
});

// Redirect to login on any 401 (unless we are already fetching /api/auth/me)
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      error.response?.status === 401 &&
      !error.config?.url?.includes('/api/auth/me') &&
      !error.config?.url?.includes('/api/auth/login')
    ) {
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type Role = 'ADMIN' | 'USER';

export interface AppUser {
  id: number;
  username: string;
  role: Role;
  mustChangePassword: boolean;
}

export interface HomeServer {
  id: number;
  name: string;
  url: string;
  ownerId: number;
  ownerUsername: string;
  basicAuthUsername: string | null;
  hasBasicAuth: boolean;
}

export type EnrollmentMode = 'DIRECT' | 'BROKER';

export interface HostServer {
  id: number;
  name: string;
  url: string;
  enrollmentPath: string | null;
  enrollmentMode: EnrollmentMode;
  brokerScope: string | null;
  ownerId: number;
  ownerUsername: string;
  basicAuthUsername: string | null;
  hasBasicAuth: boolean;
}

export interface TestUser {
  id: number;
  name: string;
  username: string;
  claims: string | null;
  academicLevel: string | null;
  alwaysDenied: boolean;
  homeServerId: number;
}

export type ActualResult = 'SUCCESS' | 'DENIED' | 'ERROR' | 'SKIPPED';

export interface Result {
  id: number;
  name: string;
  state: string | null;
  pass: string | null;
  comment: string | null;
  score: string | null;
  resultDate: string | null;
  ext: string | null;
  studyLoad: string | null;
  hostServerId: number;
}

export interface Offering {
  id: number;
  name: string;
  offeringId: string;
  offeringData: string | null;
  hostServerId: number;
}

export type TestRunStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export interface TestRun {
  id: number;
  startedAt: string;
  completedAt: string | null;
  startedBy: string;
  status: TestRunStatus;
  totalResults: number;
  statusMessage: string | null;
}

export interface ConfigStatus {
  homeServerCount: number;
  testUserCount: number;
  hostServerCount: number;
  offeringCount: number;
  totalTestCases: number;
  ready: boolean;
  issues: string[];
}

export interface MatrixCell {
  homeServerId: number;
  hostServerId: number;
  totalTests: number;
  successCount: number;
  deniedCount: number;
  errorCount: number;
  skippedCount: number;
  successRate: number;
  status: 'success' | 'partial' | 'failed' | 'error' | 'pending';
  avgDurationMs: number | null;
  warningCount: number;
}

export interface CellHistoryEntry {
  runId: number;
  total: number;
  success: number;
  status: 'success' | 'partial' | 'failed';
}

export interface ConnectivityResult {
  name: string;
  url: string;
  type: 'HOME_SERVER' | 'HOST_SERVER' | 'MOCK_OAUTH';
  reachable: boolean;
  httpStatus?: number;
  durationMs: number;
  error?: string;
}

export interface MatrixResponse {
  homeServers: Array<{ id: number; name: string }>;
  hostServers: Array<{ id: number; name: string }>;
  cells: MatrixCell[];
}

export interface TestResultDto {
  id: number;
  testUserId: number;
  testUserName: string;
  offeringId: number;
  offeringName: string;
  homeServerId: number;
  homeServerName: string;
  hostServerId: number;
  hostServerName: string;
  actualResult: ActualResult;
  errorMessage: string | null;
  stepDetails: string | null;
  startedAt: string;
  completedAt: string | null;
  hasWarnings: boolean;
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export const authApi = {
  login: (username: string, password: string) =>
    api.post<AppUser>('/api/auth/login', { username, password }),

  logout: () => api.post('/api/auth/logout'),

  me: () => api.get<AppUser>('/api/auth/me'),

  changePassword: (oldPassword: string, newPassword: string) =>
    api.post('/api/auth/change-password', {
      currentPassword: oldPassword,
      newPassword,
    }),
};

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

export const usersApi = {
  list: () => api.get<AppUser[]>('/api/users'),

  create: (data: { username: string; password: string; role: Role }) =>
    api.post<AppUser>('/api/users', data),

  update: (id: number, data: { username: string; role: Role }) =>
    api.put<AppUser>(`/api/users/${id}`, data),

  delete: (id: number) => api.delete(`/api/users/${id}`),

  resetPassword: (id: number, newPassword: string) =>
    api.post(`/api/users/${id}/reset-password`, { newPassword }),
};

// ---------------------------------------------------------------------------
// Home servers
// ---------------------------------------------------------------------------

export const homeServersApi = {
  list: () => api.get<HomeServer[]>('/api/home-servers'),

  get: (id: number) => api.get<HomeServer>(`/api/home-servers/${id}`),

  create: (data: {
    name: string;
    url: string;
    ownerId?: number;
    basicAuthUsername?: string;
    basicAuthPassword?: string;
  }) => api.post<HomeServer>('/api/home-servers', data),

  update: (
    id: number,
    data: {
      name: string;
      url: string;
      basicAuthUsername?: string;
      basicAuthPassword?: string;
    }
  ) => api.put<HomeServer>(`/api/home-servers/${id}`, data),

  delete: (id: number) => api.delete(`/api/home-servers/${id}`),

  // Test users
  listTestUsers: (homeServerId: number) =>
    api.get<TestUser[]>(`/api/home-servers/${homeServerId}/test-users`),

  createTestUser: (
    homeServerId: number,
    data: {
      name: string;
      username: string;
      claims: string | null;
      academicLevel: string | null;
      alwaysDenied: boolean;
    }
  ) => api.post<TestUser>(`/api/home-servers/${homeServerId}/test-users`, data),

  updateTestUser: (
    homeServerId: number,
    uid: number,
    data: {
      name: string;
      username: string;
      claims: string | null;
      academicLevel: string | null;
      alwaysDenied: boolean;
    }
  ) =>
    api.put<TestUser>(
      `/api/home-servers/${homeServerId}/test-users/${uid}`,
      data
    ),

  deleteTestUser: (homeServerId: number, uid: number) =>
    api.delete(`/api/home-servers/${homeServerId}/test-users/${uid}`),
};

// ---------------------------------------------------------------------------
// Host servers
// ---------------------------------------------------------------------------

export const hostServersApi = {
  list: () => api.get<HostServer[]>('/api/host-servers'),

  get: (id: number) => api.get<HostServer>(`/api/host-servers/${id}`),

  create: (data: {
    name: string;
    url: string;
    enrollmentPath?: string;
    enrollmentMode?: EnrollmentMode;
    brokerScope?: string;
    ownerId?: number;
    basicAuthUsername?: string;
    basicAuthPassword?: string;
  }) => api.post<HostServer>('/api/host-servers', data),

  update: (
    id: number,
    data: {
      name: string;
      url: string;
      enrollmentPath?: string;
      enrollmentMode?: EnrollmentMode;
      brokerScope?: string;
      basicAuthUsername?: string;
      basicAuthPassword?: string;
    }
  ) => api.put<HostServer>(`/api/host-servers/${id}`, data),

  delete: (id: number) => api.delete(`/api/host-servers/${id}`),

  // Offerings
  listOfferings: (hostServerId: number) =>
    api.get<Offering[]>(`/api/host-servers/${hostServerId}/offerings`),

  createOffering: (
    hostServerId: number,
    data: { name: string; offeringId: string; offeringData?: string }
  ) =>
    api.post<Offering>(`/api/host-servers/${hostServerId}/offerings`, data),

  updateOffering: (
    hostServerId: number,
    oid: number,
    data: { name: string; offeringId: string; offeringData?: string }
  ) =>
    api.put<Offering>(`/api/host-servers/${hostServerId}/offerings/${oid}`, data),

  deleteOffering: (hostServerId: number, oid: number) =>
    api.delete(`/api/host-servers/${hostServerId}/offerings/${oid}`),

  // Results
  listResults: (hostServerId: number) =>
    api.get<Result[]>(`/api/host-servers/${hostServerId}/results`),

  createResult: (
    hostServerId: number,
    data: {
      name: string;
      state?: string;
      pass?: string;
      comment?: string;
      score?: string;
      resultDate?: string;
      ext?: string;
      studyLoad?: string;
    }
  ) => api.post<Result>(`/api/host-servers/${hostServerId}/results`, data),

  updateResult: (
    hostServerId: number,
    rid: number,
    data: {
      name: string;
      state?: string;
      pass?: string;
      comment?: string;
      score?: string;
      resultDate?: string;
      ext?: string;
      studyLoad?: string;
    }
  ) => api.put<Result>(`/api/host-servers/${hostServerId}/results/${rid}`, data),

  deleteResult: (hostServerId: number, rid: number) =>
    api.delete(`/api/host-servers/${hostServerId}/results/${rid}`),
};

// ---------------------------------------------------------------------------
// Test runs
// ---------------------------------------------------------------------------

export const testRunsApi = {
  start: () => api.post<TestRun>('/api/test-runs'),

  list: () => api.get<TestRun[]>('/api/test-runs'),

  latest: () => api.get<TestRun>('/api/test-runs/latest'),

  get: (id: number) => api.get<TestRun>(`/api/test-runs/${id}`),

  matrix: (id: number) =>
    api.get<MatrixResponse>(`/api/test-runs/${id}/matrix`),

  detail: (id: number, homeServerId?: number, hostServerId?: number) => {
    const params: Record<string, number> = {};
    if (homeServerId !== undefined) params.homeServerId = homeServerId;
    if (hostServerId !== undefined) params.hostServerId = hostServerId;
    return api.get<TestResultDto[]>(`/api/test-runs/${id}/detail`, { params });
  },

  configStatus: () => api.get<ConfigStatus>('/api/test-runs/config-status'),

  connectivity: () => api.get<ConnectivityResult[]>('/api/test-runs/connectivity'),

  historyMatrix: () => api.get<{ cells: Record<string, CellHistoryEntry[]> }>('/api/test-runs/history-matrix'),
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

export function extractErrorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data;
    if (typeof data === 'string') return data;
    if (data && typeof data === 'object') {
      if ('error' in data) return String(data.error);
      if ('message' in data) return String(data.message);
    }
    return err.message;
  }
  if (err instanceof Error) return err.message;
  return 'An unexpected error occurred';
}
