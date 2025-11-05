import { NativeEventEmitter, NativeModules } from 'react-native';
import type { StepCountData, StepHistoryData } from './NativeRNPedometer';

// Get the native module directly from NativeModules
const RNPedometer = NativeModules.RNPedometer;

// Create event emitter for step counter updates
const eventEmitter = new NativeEventEmitter(RNPedometer);

export type { StepCountData, StepHistoryData };

export function startStepCounterUpdate(): Promise<boolean> {
  return RNPedometer.startStepCounterUpdate();
}

export function stopStepCounterUpdate(): Promise<boolean> {
  return RNPedometer.stopStepCounterUpdate();
}

export function isStepCountingAvailable(): Promise<boolean> {
  return RNPedometer.isStepCountingAvailable();
}

export function getStepHistory(days: number): Promise<StepHistoryData[]> {
  return RNPedometer.getStepHistory(days);
}

export function enableBackgroundSync(): Promise<boolean> {
  return RNPedometer.enableBackgroundSync();
}

export function disableBackgroundSync(): Promise<boolean> {
  return RNPedometer.disableBackgroundSync();
}

export function addStepCountListener(callback: (event: StepCountData) => void) {
  return eventEmitter.addListener('StepCounterUpdate', callback);
}

export function removeStepCountListener() {
  eventEmitter.removeAllListeners('StepCounterUpdate');
}

const RNPedometerModule = {
  startStepCounterUpdate,
  stopStepCounterUpdate,
  isStepCountingAvailable,
  getStepHistory,
  enableBackgroundSync,
  disableBackgroundSync,
  addStepCountListener,
  removeStepCountListener,
};

export default RNPedometerModule;
