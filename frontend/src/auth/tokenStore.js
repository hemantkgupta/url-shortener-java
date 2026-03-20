/**
 * In-memory token store. Holds the Google ID token so the axios
 * instance (a module singleton) can read it without React hooks.
 */
let _idToken = null;

export const setToken = (token) => { _idToken = token; };
export const getToken = () => _idToken;
export const clearToken = () => { _idToken = null; };
