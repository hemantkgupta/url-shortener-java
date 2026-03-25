export const TEST_AUTH_STORAGE_KEY = 'hyperShort:e2e-auth-mode';
export const TEST_AUTH_STORAGE_VALUE = 'mock';
export const TEST_AUTH_TOKEN = 'e2e-local-test-token';

export const TEST_USER_PROFILE = {
  name: 'Playwright Tester',
  email: 'playwright@example.com',
  picture: '',
  sub: 'playwright-test-user',
};

export function isLocalTestAuthEnabled() {
  if (typeof window === 'undefined') {
    return false;
  }

  return window.localStorage.getItem(TEST_AUTH_STORAGE_KEY) === TEST_AUTH_STORAGE_VALUE;
}
