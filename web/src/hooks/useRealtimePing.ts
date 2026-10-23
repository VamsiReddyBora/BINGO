import { useState, useEffect } from 'react';
import { NetworkPingMonitor } from '../network/pingMonitor';

/**
 * React hook that subscribes to NetworkPingMonitor.
 * Returns the smoothed real-time ping in milliseconds, updated continuously every 1 second.
 */
export function useRealtimePing(): number {
  const [ping, setPing] = useState<number>(() => NetworkPingMonitor.getPing());

  useEffect(() => {
    const unsubscribe = NetworkPingMonitor.subscribe((newPing) => {
      setPing(newPing);
    });
    return unsubscribe;
  }, []);

  return ping;
}
