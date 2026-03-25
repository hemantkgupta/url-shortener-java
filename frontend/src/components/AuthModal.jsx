import React from 'react';
import { GoogleLogin } from '@react-oauth/google';
import { useAuth } from '../context/useAuth';
import { X, User } from 'lucide-react';
import { Button } from './ui/button';
import { isLocalTestAuthEnabled } from '../auth/e2eAuth';

export default function AuthModal({ isOpen, onClose }) {
  const { signIn, signInAsTestUser } = useAuth();
  const hasGoogleClientId = Boolean(import.meta.env.VITE_GOOGLE_CLIENT_ID);
  const enableLocalTestAuth = isLocalTestAuthEnabled();

  if (!isOpen) return null;

  const handleGoogleSuccess = (credentialResponse) => {
    signIn(credentialResponse);
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="bg-white dark:bg-slate-900 w-full max-w-sm rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-800 overflow-hidden animate-in zoom-in-95 duration-200">
        <div className="p-6 flex items-center justify-between border-b border-slate-100 dark:border-slate-800">
          <h2 className="text-xl font-bold text-slate-900 dark:text-white">Sign In</h2>
          <button
            onClick={onClose}
            aria-label="Close sign in dialog"
            className="p-2 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-full transition-colors"
          >
            <X className="w-5 h-5 text-slate-500" />
          </button>
        </div>

        <div className="p-8 flex flex-col items-center gap-6">
          <p className="text-sm text-slate-500 dark:text-slate-400 text-center">
            Sign in to save your links and use custom aliases.
          </p>

          {hasGoogleClientId ? (
            <GoogleLogin
              onSuccess={handleGoogleSuccess}
              onError={() => console.error('Google sign-in failed')}
              useOneTap={false}
              theme="outline"
              size="large"
              shape="rectangular"
              text="signin_with"
            />
          ) : (
            <div className="w-full rounded-xl border border-dashed border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-950 px-4 py-3 text-center text-sm text-slate-500 dark:text-slate-400">
              Google sign-in is not configured in this environment.
            </div>
          )}

          {enableLocalTestAuth && (
            <Button
              variant="secondary"
              className="w-full h-11 font-medium rounded-xl bg-slate-900 text-white hover:bg-slate-800"
              onClick={() => {
                signInAsTestUser();
                onClose();
              }}
              data-testid="auth-local-login"
            >
              <User className="w-4 h-4 mr-2" />
              Use local test account
            </Button>
          )}

          <div className="relative w-full py-2">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-slate-100 dark:border-slate-800" />
            </div>
            <div className="relative flex justify-center text-xs uppercase">
              <span className="bg-white dark:bg-slate-900 px-2 text-slate-500">Or</span>
            </div>
          </div>

          <Button
            variant="outline"
            className="w-full h-11 font-medium border-slate-200 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800 rounded-xl"
            onClick={onClose}
          >
            <User className="w-4 h-4 mr-2" />
            Continue without signing in
          </Button>
        </div>
      </div>
    </div>
  );
}
