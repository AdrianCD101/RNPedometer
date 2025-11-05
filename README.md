# @mmeow223/rnpedometer

## React Native Pedometer

`@mmeow223/rnpedometer` is a React Native module that provides access to the pedometer of a mobile device, allowing you to listen for step count updates in real-time.

✅ **Full iOS and Android Support** Both platforms are fully supported!

## Features

* Start and stop step counting.
* Check if step counting is available on the device.
* Listen for real-time step count updates.
* **Persistent daily step tracking** - counts persist across app restarts and continue even when app is closed
* Automatic daily reset at midnight
* Uses the native pedometer APIs for both iOS and Android.

## How It Works

The module uses hardware-level step counting that works even when your app is closed:

**Android**: Uses `Sensor.TYPE_STEP_COUNTER` which tracks steps at the OS level. Steps are counted continuously by the device, and the module saves your daily baseline to calculate today's total steps.

**iOS**: Uses CoreMotion's `CMPedometer` which provides system-level step tracking. Step counts are automatically tracked by iOS and persist across app restarts.

**Daily Reset**: Both platforms automatically reset step counts at midnight to track daily totals, just like Apple Fitness!

## Installation

```sh
npm install @mmeow223/rnpedometer
```

or using Yarn:

```sh
yarn add @mmeow223/rnpedometer
```

### iOS Setup

For iOS, ensure that `NSMotionUsageDescription` is added to your `Info.plist` file:

```xml
<key>NSMotionUsageDescription</key>
<string>We use motion data to count your steps.</string>
```

Then, run:

```sh
cd ios
pod install
```

### Android Setup

For Android, you need to request the `ACTIVITY_RECOGNITION` permission in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
```

## Usage

Here's an example implementation of how to use `@mmeow223/rnpedometer` in your React Native app:

```tsx
import React, { useEffect, useState } from 'react';
import { Text, View, Button, PermissionsAndroid, Platform } from 'react-native';
import {
  startStepCounterUpdate,
  stopStepCounterUpdate,
  isStepCountingAvailable,
  addStepCountListener,
  removeStepCountListener,
  type StepCountData,
} from '@mmeow223/rnpedometer';

export default function App() {
  const [isAvailable, setIsAvailable] = useState(false);
  const [isTracking, setIsTracking] = useState(false);
  const [steps, setSteps] = useState(0);
  const [totalSteps, setTotalSteps] = useState(0);

  useEffect(() => {
    checkAvailability();

    if (Platform.OS === 'android') {
      requestPermissions();
    }

    return () => {
      if (isTracking) {
        stopTracking();
      }
    };
  }, []);

  const requestPermissions = async () => {
    try {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACTIVITY_RECOGNITION
      );
      return granted === PermissionsAndroid.RESULTS.GRANTED;
    } catch (err) {
      console.error('Failed to request permission:', err);
      return false;
    }
  };

  const checkAvailability = async () => {
    try {
      const available = await isStepCountingAvailable();
      setIsAvailable(available);
    } catch (error) {
      console.error('Failed to check pedometer availability:', error);
    }
  };

  const startTracking = async () => {
    try {
      await startStepCounterUpdate();
      setIsTracking(true);

      const subscription = addStepCountListener((data: StepCountData) => {
        setSteps(data.steps);
        setTotalSteps(data.totalSteps);
      });
      return subscription;
    } catch (error) {
      console.error('Failed to start step tracking:', error);
    }
  };

  const stopTracking = async () => {
    try {
      await stopStepCounterUpdate();
      removeStepCountListener();
      setIsTracking(false);
    } catch (error) {
      console.error('Failed to stop step tracking:', error);
    }
  };

  if (!isAvailable) {
    return (
      <View>
        <Text>Step counting is not available on this device</Text>
      </View>
    );
  }

  return (
    <View>
      <Text>Steps this session: {steps}</Text>
      <Text>Total steps: {totalSteps}</Text>
      {!isTracking ? (
        <Button title="Start Tracking" onPress={startTracking} />
      ) : (
        <Button title="Stop Tracking" onPress={stopTracking} />
      )}
    </View>
  );
}
```

## API

### `startStepCounterUpdate(): Promise<boolean>`

Starts step counting and returns a promise resolving to `true` if successful.

### `stopStepCounterUpdate(): Promise<boolean>`

Stops step counting.

### `isStepCountingAvailable(): Promise<boolean>`

Checks if step counting is available on the device.

### `addStepCountListener(callback: (event: StepCountData) => void): void`

Registers a listener to receive step count updates.

### `removeStepCountListener(): void`

Removes all registered step count listeners.

## License

MIT

