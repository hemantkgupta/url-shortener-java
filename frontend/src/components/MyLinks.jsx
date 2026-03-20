import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { getUserHistory } from '../services/api';
import { ExternalLink, Calendar, Link as LinkIcon } from 'lucide-react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "./ui/table";
import { Card } from "./ui/card";
import { Button } from "./ui/button";

export default function MyLinks() {
  const { data: history, isLoading, error, refetch } = useQuery({
    queryKey: ['user-history'],
    queryFn: getUserHistory,
  });

  if (isLoading) {
    return (
      <div className="flex flex-col items-center justify-center py-12 space-y-4">
        <div className="w-12 h-12 border-4 border-blue-600 border-t-transparent rounded-full animate-spin"></div>
        <p className="text-slate-500 animate-pulse">Loading your links...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="py-12 text-center">
        <p className="text-red-500 font-medium">Failed to load your links.</p>
        <Button variant="link" onClick={() => refetch()} className="mt-2 text-blue-600">
          Try again
        </Button>
      </div>
    );
  }

  if (!history || history.length === 0) {
    return (
      <div className="py-12 text-center bg-slate-50 dark:bg-slate-900/50 rounded-2xl border-2 border-dashed border-slate-200 dark:border-slate-800">
        <div className="w-16 h-16 bg-slate-100 dark:bg-slate-800 rounded-full flex items-center justify-center mx-auto mb-4">
          <LinkIcon className="w-8 h-8 text-slate-400" />
        </div>
        <h3 className="text-lg font-bold text-slate-900 dark:text-white mb-2">No links yet</h3>
        <p className="text-slate-500 dark:text-slate-400 max-w-xs mx-auto">
          Start shortening URLs to see them here!
        </p>
      </div>
    );
  }

  return (
    <div className="w-full max-w-4xl animate-in fade-in slide-in-from-bottom-4 duration-500">
      <Card className="overflow-hidden border-slate-200 dark:border-slate-800 shadow-sm bg-white dark:bg-slate-950">
        <Table>
          <TableHeader className="bg-slate-50/50 dark:bg-slate-900/50">
            <TableRow>
              <TableHead className="px-6 py-4 font-bold text-slate-600 dark:text-slate-400">Short Link</TableHead>
              <TableHead className="px-6 py-4 font-bold text-slate-600 dark:text-slate-400">Destination</TableHead>
              <TableHead className="px-6 py-4 font-bold text-slate-600 dark:text-slate-400 text-right">Created</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {history.map((item) => (
              <TableRow key={item.id} className="hover:bg-slate-50/50 dark:hover:bg-slate-900/50 transition-colors group">
                <TableCell className="px-6 py-5">
                  <div className="flex items-center gap-2">
                    <a
                      href={`${import.meta.env.VITE_SHORT_LINK_BASE_URL || 'http://localhost:10001'}/${item.id}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="font-bold text-blue-600 dark:text-blue-400 hover:underline flex items-center gap-1"
                    >
                      {(import.meta.env.VITE_SHORT_LINK_BASE_URL || 'sho.rt').replace(/^https?:\/\//, '')}/{item.id}
                      <ExternalLink className="w-3 h-3 opacity-0 group-hover:opacity-100 transition-opacity" />
                    </a>
                  </div>
                </TableCell>
                <TableCell className="px-6 py-5">
                  <div className="max-w-xs sm:max-w-md overflow-hidden text-slate-600 dark:text-slate-400 text-sm font-medium" title={item.long_url}>
                    {item.long_url}
                  </div>
                </TableCell>
                <TableCell className="px-6 py-5 text-right">
                  <div className="flex items-center justify-end gap-2 text-slate-500 dark:text-slate-500 text-sm">
                    <Calendar className="w-3 h-3" />
                    {new Date(item.created_at).toLocaleDateString(undefined, {
                      month: 'short',
                      day: 'numeric',
                      year: 'numeric'
                    })}
                  </div>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Card>
    </div>
  );
}
