import axios from 'axios';
import { getToken } from '../auth/tokenStore';

const api = axios.create({
  baseURL: '/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Attach Google ID token as Bearer if the user is signed in
api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const shortenUrl = async (longUrl, customSlug) => {
  const response = await api.post('/shorten', {
    long_url: longUrl,
    custom_slug: customSlug,
  });
  return response.data;
};

export const getTopAnalytics = async (page = 1, limit = 10) => {
  const response = await api.get('/analytics/top', {
    params: { page, limit },
  });
  return response.data;
};

export const getUserHistory = async () => {
  const response = await api.get('/history');
  return response.data;
};

export default api;
