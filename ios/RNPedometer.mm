#import "RNPedometer.h"
#import <CoreMotion/CoreMotion.h>

static NSString *const kRNPedometerInitialStepCount = @"RNPedometerInitialStepCount";
static NSString *const kRNPedometerCurrentStepCount = @"RNPedometerCurrentStepCount";
static NSString *const kRNPedometerSavedDate = @"RNPedometerSavedDate";
static NSString *const kRNPedometerHistoryPrefix = @"RNPedometerHistory_";
static NSInteger const kRNPedometerMaxHistoryDays = 30;

@interface RNPedometer()
@property (nonatomic, strong) CMPedometer *pedometer;
@property (nonatomic, strong) NSNumber *initialStepCount;
@property (nonatomic, strong) NSNumber *currentStepCount;
@property (nonatomic, assign) BOOL isTracking;
@property (nonatomic, assign) NSInteger listenerCount;
@end

@implementation RNPedometer

RCT_EXPORT_MODULE()

- (instancetype)init {
  self = [super init];
  if (self) {
    _pedometer = [[CMPedometer alloc] init];
    _initialStepCount = nil;
    _currentStepCount = @0;
    _isTracking = NO;
    _listenerCount = 0;

    // Load persisted data
    [self loadPersistedData];
  }
  return self;
}

- (void)loadPersistedData {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSString *savedDate = [defaults stringForKey:kRNPedometerSavedDate];
  NSString *currentDate = [self getCurrentDate];

  // If it's a new day, save yesterday's data to history and reset counters
  if (savedDate && ![savedDate isEqualToString:currentDate]) {
    // Calculate yesterday's total steps before clearing
    NSNumber *savedInitial = [defaults objectForKey:kRNPedometerInitialStepCount];
    NSNumber *savedCurrent = [defaults objectForKey:kRNPedometerCurrentStepCount];

    if (savedInitial && savedCurrent) {
      NSInteger yesterdaySteps = [savedCurrent integerValue] - [savedInitial integerValue];
      [self saveStepHistory:savedDate steps:yesterdaySteps];
    }

    [self clearPersistedData];

    // Backfill any missing days from CMPedometer
    [self backfillMissingDaysFromCMPedometer:savedDate];
  } else if ([savedDate isEqualToString:currentDate]) {
    // Load saved values for today
    NSNumber *savedInitial = [defaults objectForKey:kRNPedometerInitialStepCount];
    NSNumber *savedCurrent = [defaults objectForKey:kRNPedometerCurrentStepCount];

    if (savedInitial) {
      _initialStepCount = savedInitial;
      _currentStepCount = savedCurrent ?: @0;
    }
  }

  // Clean up old history (keep only last MAX_HISTORY_DAYS days)
  [self cleanupOldHistory];
}

- (void)savePersistedData {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  if (self.initialStepCount) {
    [defaults setObject:self.initialStepCount forKey:kRNPedometerInitialStepCount];
    [defaults setObject:self.currentStepCount forKey:kRNPedometerCurrentStepCount];
    [defaults setObject:[self getCurrentDate] forKey:kRNPedometerSavedDate];
    [defaults synchronize];
  }
}

- (void)clearPersistedData {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  [defaults removeObjectForKey:kRNPedometerInitialStepCount];
  [defaults removeObjectForKey:kRNPedometerCurrentStepCount];
  [defaults removeObjectForKey:kRNPedometerSavedDate];
  [defaults synchronize];

  _initialStepCount = nil;
  _currentStepCount = @0;
}

- (NSString *)getCurrentDate {
  NSDateFormatter *dateFormatter = [[NSDateFormatter alloc] init];
  [dateFormatter setDateFormat:@"yyyy-MM-dd"];
  return [dateFormatter stringFromDate:[NSDate date]];
}

- (void)saveStepHistory:(NSString *)date steps:(NSInteger)steps {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSString *key = [NSString stringWithFormat:@"%@%@", kRNPedometerHistoryPrefix, date];
  [defaults setInteger:steps forKey:key];
  [defaults synchronize];
}

- (void)cleanupOldHistory {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSCalendar *calendar = [NSCalendar currentCalendar];
  NSDate *cutoffDate = [calendar dateByAddingUnit:NSCalendarUnitDay
                                            value:-kRNPedometerMaxHistoryDays
                                           toDate:[NSDate date]
                                          options:0];

  NSDateFormatter *dateFormatter = [[NSDateFormatter alloc] init];
  [dateFormatter setDateFormat:@"yyyy-MM-dd"];
  NSString *cutoffDateString = [dateFormatter stringFromDate:cutoffDate];

  NSDictionary *allDefaults = [defaults dictionaryRepresentation];
  for (NSString *key in allDefaults.allKeys) {
    if ([key hasPrefix:kRNPedometerHistoryPrefix]) {
      NSString *date = [key substringFromIndex:kRNPedometerHistoryPrefix.length];
      if ([date compare:cutoffDateString] == NSOrderedAscending) {
        [defaults removeObjectForKey:key];
      }
    }
  }
  [defaults synchronize];
}

- (NSArray *)getStepHistoryInternal:(NSInteger)days {
  NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
  NSMutableArray *historyArray = [NSMutableArray array];
  NSCalendar *calendar = [NSCalendar currentCalendar];
  NSDateFormatter *dateFormatter = [[NSDateFormatter alloc] init];
  [dateFormatter setDateFormat:@"yyyy-MM-dd"];

  // Get history for the last 'days' days (not including today)
  for (NSInteger i = 1; i <= days; i++) {
    NSDate *date = [calendar dateByAddingUnit:NSCalendarUnitDay
                                       value:-i
                                      toDate:[NSDate date]
                                     options:0];
    NSString *dateString = [dateFormatter stringFromDate:date];
    NSString *key = [NSString stringWithFormat:@"%@%@", kRNPedometerHistoryPrefix, dateString];
    NSInteger steps = [defaults integerForKey:key];

    [historyArray addObject:@{
      @"date": dateString,
      @"steps": @(steps)
    }];
  }

  return historyArray;
}

- (void)backfillMissingDaysFromCMPedometer:(NSString *)lastSavedDate {
  // Only backfill if CMPedometer data is available
  if (![CMPedometer isStepCountingAvailable]) {
    return;
  }

  NSDateFormatter *dateFormatter = [[NSDateFormatter alloc] init];
  [dateFormatter setDateFormat:@"yyyy-MM-dd"];
  NSDate *lastDate = [dateFormatter dateFromString:lastSavedDate];
  NSDate *today = [NSDate date];

  if (!lastDate) {
    return;
  }

  NSCalendar *calendar = [NSCalendar currentCalendar];
  NSDateComponents *components = [calendar components:NSCalendarUnitDay
                                              fromDate:lastDate
                                                toDate:today
                                               options:0];
  NSInteger daysDiff = components.day;

  // If more than 1 day has passed, backfill from CMPedometer (up to 7 days max)
  if (daysDiff > 1 && daysDiff <= 7) {
    for (NSInteger i = 1; i < daysDiff; i++) {
      NSDate *targetDate = [calendar dateByAddingUnit:NSCalendarUnitDay
                                               value:i
                                              toDate:lastDate
                                             options:0];

      // Get start and end of the day
      NSDate *startOfDay = [calendar startOfDayForDate:targetDate];
      NSDate *endOfDay = [calendar dateByAddingUnit:NSCalendarUnitDay
                                              value:1
                                             toDate:startOfDay
                                            options:0];

      // Query CMPedometer for that day's data
      [self.pedometer queryPedometerDataFromDate:startOfDay
                                          toDate:endOfDay
                                     withHandler:^(CMPedometerData * _Nullable pedometerData, NSError * _Nullable error) {
        if (!error && pedometerData && pedometerData.numberOfSteps) {
          NSString *dateString = [dateFormatter stringFromDate:targetDate];
          NSInteger steps = [pedometerData.numberOfSteps integerValue];
          [self saveStepHistory:dateString steps:steps];
        }
      }];
    }
  }
}

+ (BOOL)requiresMainQueueSetup {
  return NO;
}

#pragma mark - RCTEventEmitter Methods

- (NSArray<NSString *> *)supportedEvents {
  return @[@"StepCounterUpdate"];
}

- (void)startObserving {
  self.listenerCount += 1;
}

- (void)stopObserving {
  self.listenerCount -= 1;
  
  // If no listeners, stop tracking
  if (self.listenerCount <= 0) {
    self.listenerCount = 0;
    if (self.isTracking) {
      [self stopPedometerUpdates];
    }
  }
}

#pragma mark - NativeRNPedometerSpec Implementation

- (void)addListener:(NSString *)eventName {
  [super addListener:eventName];
}

- (void)removeListeners:(double)count {
  [super removeListeners:count];
}

- (void)isStepCountingAvailable:(RCTPromiseResolveBlock)resolve
                         reject:(RCTPromiseRejectBlock)reject {
  BOOL isAvailable = [CMPedometer isStepCountingAvailable];
  resolve(@(isAvailable));
}

- (void)startStepCounterUpdate:(RCTPromiseResolveBlock)resolve
                        reject:(RCTPromiseRejectBlock)reject {
  if (self.isTracking) {
    resolve(@(NO));
    return;
  }
  
  if (![CMPedometer isStepCountingAvailable]) {
    reject(@"E_STEP_COUNTER", @"Step counting is not available on this device", nil);
    return;
  }
  
  NSDate *now = [NSDate date];
  __weak RNPedometer *weakSelf = self;
  [self.pedometer startPedometerUpdatesFromDate:now withHandler:^(CMPedometerData * _Nullable pedometerData, NSError * _Nullable error) {
    RNPedometer *strongSelf = weakSelf;
    if (!strongSelf) {
      return;
    }
    
    if (error) {
      // Don't reject promise since startPedometerUpdates may have succeeded
      // Just log the error and continue
      NSLog(@"Error receiving pedometer updates: %@", error);
      return;
    }
    
    if (!pedometerData) {
      return;
    }
    
    // Initialize step count tracking
    if (!strongSelf.initialStepCount) {
      strongSelf.initialStepCount = pedometerData.numberOfSteps;
      strongSelf.currentStepCount = pedometerData.numberOfSteps;
      [strongSelf savePersistedData];
    }

    // Calculate steps since last update
    NSInteger stepsDelta = [pedometerData.numberOfSteps integerValue] - [strongSelf.currentStepCount integerValue];
    strongSelf.currentStepCount = pedometerData.numberOfSteps;

    // Calculate total steps since starting tracking
    NSInteger totalSteps = [pedometerData.numberOfSteps integerValue] - [strongSelf.initialStepCount integerValue];

    // Save updated values
    [strongSelf savePersistedData];

    if (strongSelf.listenerCount > 0) {
      [strongSelf sendEventWithName:@"StepCounterUpdate" body:@{
        @"steps": @(stepsDelta),
        @"totalSteps": @(totalSteps),
        @"timestamp": @([[NSDate date] timeIntervalSince1970] * 1000)
      }];
    }
  }];
  
  self.isTracking = YES;
  resolve(@(YES));
}

- (void)stopStepCounterUpdate:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject {
  if (!self.isTracking) {
    resolve(@(NO));
    return;
  }
  
  [self stopPedometerUpdates];
  resolve(@(YES));
}

- (void)stopPedometerUpdates {
  [self.pedometer stopPedometerUpdates];
  self.isTracking = NO;
}

- (void)getStepHistory:(double)days
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  @try {
    NSInteger requestedDays = (NSInteger)days;
    if (requestedDays < 1) requestedDays = 1;
    if (requestedDays > kRNPedometerMaxHistoryDays) requestedDays = kRNPedometerMaxHistoryDays;

    NSArray *history = [self getStepHistoryInternal:requestedDays];
    resolve(history);
  } @catch (NSException *exception) {
    reject(@"E_HISTORY_ERROR",
           [NSString stringWithFormat:@"Failed to retrieve step history: %@", exception.reason],
           nil);
  }
}

- (void)enableBackgroundSync:(RCTPromiseResolveBlock)resolve
                      reject:(RCTPromiseRejectBlock)reject {
  // iOS doesn't need explicit background sync - CMPedometer handles it automatically
  // When app opens, we backfill any missing days from CMPedometer
  resolve(@(YES));
}

- (void)disableBackgroundSync:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject {
  // iOS doesn't have active background sync, so nothing to disable
  resolve(@(YES));
}

#pragma mark - Turbo Module Requirements

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
(const facebook::react::ObjCTurboModule::InitParams &)params
{
  return std::make_shared<facebook::react::NativeRNPedometerSpecJSI>(params);
}

@end
