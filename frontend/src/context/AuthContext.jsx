import React, { createContext, useState, useCallback } from 'react';
import { googleLogout } from '@react-oauth/google';
import { setToken, clearToken } from '../auth/tokenStore';
import { isLocalTestAuthEnabled, TEST_AUTH_TOKEN, TEST_USER_PROFILE } from '../auth/e2eAuth';

const AuthContext = createContext();

/**
 * Decodes the payload of a Google ID token (JWT) without verifying the
 * signature — verification happens on the backend via Google's JWKS endpoint.
 */
function decodeJwtPayload(token) {
  try {
    return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
  } catch {
    return null;
  }
}

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null); // { name, email, picture, sub }

  const signIn = useCallback((credentialResponse) => {
    const hasCredential = Boolean(credentialResponse?.credential);
    const payload = hasCredential
      ? decodeJwtPayload(credentialResponse.credential)
      : credentialResponse;

    if (!payload) return;

    if (hasCredential) {
      setToken(credentialResponse.credential);
    } else if (isLocalTestAuthEnabled()) {
      setToken(TEST_AUTH_TOKEN);
    } else {
      return;
    }

    setUser({
      name: payload.name,
      email: payload.email,
      picture: payload.picture,
      sub: payload.sub,
      isAnonymous: false,
    });
  }, []);

  const signInAsTestUser = useCallback(() => {
    if (!isLocalTestAuthEnabled()) {
      return;
    }

    setToken(TEST_AUTH_TOKEN);
    setUser({
      ...TEST_USER_PROFILE,
      isAnonymous: false,
    });
  }, []);

  const signOut = useCallback(() => {
    googleLogout();
    clearToken();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, signIn, signInAsTestUser, signOut }}>
      {children}
    </AuthContext.Provider>
  );
};

export { AuthContext };
