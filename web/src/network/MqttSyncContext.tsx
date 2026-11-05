import React, { createContext, useContext } from 'react';
import { MqttRoomManager, roomSync } from './mqttSync';

export const MqttSyncContext = createContext<MqttRoomManager>(roomSync);

export const useMqttSync = (): MqttRoomManager => {
  return useContext(MqttSyncContext);
};

export const MqttSyncProvider: React.FC<{
  manager: MqttRoomManager;
  children: React.ReactNode;
}> = ({ manager, children }) => {
  return (
    <MqttSyncContext.Provider value={manager}>
      {children}
    </MqttSyncContext.Provider>
  );
};
