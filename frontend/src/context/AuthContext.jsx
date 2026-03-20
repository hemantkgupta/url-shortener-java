import React, { createContext, useContext, useState, useCallback } from 'react';
import { googleLogout } from '@react-oauth/google';
import { setToken, clearToken } from '../auth/tokenStore';

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
    const payload = decodeJwtPayload(credentialResponse.credential);
    if (!payload) return;
    setToken(credentialResponse.credential);
    setUser({
      name: payload.name,
      email: payload.email,
      picture: payload.picture,
      sub: payload.sub,
      isAnonymous: false,
    });
  }, []);

  const signOut = useCallback(() => {
    googleLogout();
    clearToken();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, signIn, signOut }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
