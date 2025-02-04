/*
 * Copyright 2013, Leanplum, Inc. All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.leanplum;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;

import com.leanplum.annotations.Parser;
import com.leanplum.callbacks.PostponableAction;
import com.leanplum.internal.ActionManager;
import com.leanplum.internal.Constants;
import com.leanplum.internal.LeanplumInternal;
import com.leanplum.internal.Log;

import com.leanplum.internal.OperationQueue;
import com.leanplum.utils.BuildUtil;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * Utility class for handling activity lifecycle events. Call these methods from your activity if
 * you don't extend one of the Leanplum*Activity classes.
 *
 * @author Andrew First
 */
public class LeanplumActivityHelper {
  /**
   * Whether any of the activities are paused.
   */
  static boolean isActivityPaused;
  private static Set<Class> ignoredActivityClasses;

  /**
   * Whether lifecycle callbacks were registered. This is only supported on Android OS &gt;= 4.0.
   */
  private static boolean registeredCallbacks;

  private static Activity currentActivity;

  private final Activity activity;
  private LeanplumResources res;
  private LeanplumInflater inflater;

  private static final Queue<Runnable> pendingActions = new LinkedList<>();
  private static final Runnable runPendingActionsRunnable = new Runnable() {
    @Override
    public void run() {
      runPendingActions();
    }
  };

  public LeanplumActivityHelper(Activity activity) {
    this.activity = activity;
    Leanplum.setApplicationContext(activity.getApplicationContext());
    Parser.parseVariables(activity);
  }

  /**
   * Set activity and run pending actions
   */
  public static void setCurrentActivity(Context context) {
    if (context instanceof Activity) {
      currentActivity = (Activity) context;

      // run pending actions if any upon start
      LeanplumInternal.addStartIssuedHandler(runPendingActionsRunnable);
    }
  }

  /**
   * Retrieves the currently active activity.
   */
  public static Activity getCurrentActivity() {
    return currentActivity;
  }

  /**
   * Retrieves if the activity is paused.
   */
  public static boolean isActivityPaused() {
    return isActivityPaused;
  }

  /**
   * Class provides additional functionality to handle payloads of push notifications built to
   * comply with new Android 12 restrictions on using notification trampolines.
   * The intent contains the message bundle which is used to run the open action and to track
   * 'Push Opened' and 'Open' events.
   */
  @TargetApi(31)
  static class NoTrampolinesLifecycleCallbacks extends LeanplumLifecycleCallbacks {

    @Override
    public void onActivityResumed(Activity activity) {
      super.onActivityResumed(activity);

      if (activity.getIntent() != null) {
        Bundle extras = activity.getIntent().getExtras();
        if (extras != null && extras.containsKey(Constants.Keys.PUSH_MESSAGE_TEXT)) {
          OperationQueue.sharedInstance().addParallelOperation(
              () -> handleNotificationPayload(extras));
        }
      }
    }

    private void handleNotificationPayload(Bundle message) {
      try {
        Class.forName("com.leanplum.LeanplumPushService")
            .getDeclaredMethod("onActivityNotificationClick", Bundle.class)
            .invoke(null, message);
      } catch (Throwable t) {
        Log.e("Push Notification action not run. Did you forget leanplum-push module?", t);
      }
    }
  }

  static class LeanplumLifecycleCallbacks implements ActivityLifecycleCallbacks {
    @Override
    public void onActivityStopped(Activity activity) {
      try {
        onStop(activity);
      } catch (Throwable t) {
        Log.exception(t);
      }
    }

    @Override
    public void onActivityResumed(final Activity activity) {
      try {
        onResume(activity);
        if (Leanplum.isScreenTrackingEnabled()) {
          Leanplum.advanceTo(activity.getLocalClassName());
        }
      } catch (Throwable t) {
        Log.exception(t);
      }
    }

    @Override
    public void onActivityPaused(Activity activity) {
      try {
        onPause(activity);
      } catch (Throwable t) {
        Log.exception(t);
      }
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }
  }

  /**
   * Enables lifecycle callbacks for Android devices with Android OS &gt;= 4.0
   */
  public static void enableLifecycleCallbacks(final Application app) {
    Leanplum.setApplicationContext(app.getApplicationContext());

    if (BuildUtil.shouldDisableTrampolines(app)) {
      app.registerActivityLifecycleCallbacks(new NoTrampolinesLifecycleCallbacks());
    } else {
      app.registerActivityLifecycleCallbacks(new LeanplumLifecycleCallbacks());
    }

    registeredCallbacks = true;
    // run pending actions if any upon start
    LeanplumInternal.addStartIssuedHandler(runPendingActionsRunnable);
  }

  public LeanplumResources getLeanplumResources() {
    return getLeanplumResources(null);
  }

  public LeanplumResources getLeanplumResources(Resources baseResources) {
    if (res != null) {
      return res;
    }
    if (baseResources == null) {
      baseResources = activity.getResources();
    }
    if (baseResources instanceof LeanplumResources) {
      return (LeanplumResources) baseResources;
    }
    res = new LeanplumResources(baseResources);
    return res;
  }

  /**
   * Sets the view from a layout file.
   */
  public void setContentView(final int layoutResID) {
    if (inflater == null) {
      inflater = LeanplumInflater.from(activity);
    }
    activity.setContentView(inflater.inflate(layoutResID));
  }

  @SuppressWarnings("unused")
  private static void onPause(Activity activity) {
    isActivityPaused = true;
  }

  /**
   * Call this when your activity gets paused.
   */
  public void onPause() {
    try {
      if (!registeredCallbacks) {
        onPause(activity);
      }
    } catch (Throwable t) {
      Log.exception(t);
    }
  }

  private static void onResume(Activity activity) {
    isActivityPaused = false;
    currentActivity = activity;
    if (LeanplumInternal.isPaused() || LeanplumInternal.hasStartedInBackground()) {
      Leanplum.resume();
      LocationManager locationManager = ActionManager.getLocationManager();
      if (locationManager != null) {
        locationManager.updateGeofencing();
        locationManager.updateUserLocation();
      }
    }

    // Pending actions execution triggered, but Leanplum.start() may not be done yet.
    LeanplumInternal.addStartIssuedHandler(runPendingActionsRunnable);
  }

  /**
   * Call this when your activity gets resumed.
   */
  public void onResume() {
    try {
      if (!registeredCallbacks) {
        onResume(activity);
      }
    } catch (Throwable t) {
      Log.exception(t);
    }
  }

  private static void onStop(Activity activity) {
    // onStop is called when the activity gets hidden, and is
    // called after onPause.
    //
    // However, if we're switching to another activity, that activity
    // will call onResume, so we shouldn't pause if that's the case.
    //
    // Thus, we can call pause from here, only if all activities are paused.
    if (isActivityPaused) {
      Leanplum.pause();
      LocationManager locationManager = ActionManager.getLocationManager();
      if (locationManager != null) {
        locationManager.updateGeofencing();
      }
    }
    if (currentActivity != null && currentActivity.equals(activity)) {
      // Don't leak activities.
      currentActivity = null;
    }
  }

  /**
   * Call this when your activity gets stopped.
   */
  public void onStop() {
    try {
      if (!registeredCallbacks) {
        onStop(activity);
      }
    } catch (Throwable t) {
      Log.exception(t);
    }
  }

  /**
   * Enqueues a callback to invoke when an activity reaches in the foreground.
   */
  public static void queueActionUponActive(Runnable action) {
    try {
      if (currentActivity != null && !currentActivity.isFinishing() && !isActivityPaused &&
          (!(action instanceof PostponableAction) || !isActivityClassIgnored(currentActivity))) {
        action.run();
      } else {
        synchronized (pendingActions) {
          pendingActions.add(action);
        }
      }
    } catch (Throwable t) {
      Log.exception(t);
    }
  }

  /**
   * Runs any pending actions that have been queued.
   */
  private static void runPendingActions() {
    if (isActivityPaused || currentActivity == null) {
      // Trying to run pending actions, but no activity is resumed. Skip.
      return;
    }

    Queue<Runnable> runningActions;
    synchronized (pendingActions) {
      runningActions = new LinkedList<>(pendingActions);
      pendingActions.clear();
    }
    for (Runnable action : runningActions) {
      // If postponable callback and current activity should be skipped, then postpone.
      if (action instanceof PostponableAction && isActivityClassIgnored(currentActivity)) {
        pendingActions.add(action);
      } else {
        action.run();
      }
    }
  }

  /**
   * Whether or not an activity is configured to not show messages.
   *
   * @param activity The activity to check.
   * @return Whether or not the activity is ignored.
   */
  private static boolean isActivityClassIgnored(Activity activity) {
    return ignoredActivityClasses != null && ignoredActivityClasses.contains(activity.getClass());
  }

  /**
   * Does not show messages for the provided activity classes.
   *
   * @param activityClasses The activity classes to not show messages on.
   */
  public static void deferMessagesForActivities(Class... activityClasses) {
    // Check if valid arguments are provided.
    if (activityClasses == null || activityClasses.length == 0) {
      return;
    }
    // Lazy instantiate activityClasses set.
    if (ignoredActivityClasses == null) {
      ignoredActivityClasses = new HashSet<>(activityClasses.length);
    }
    // Add all class names to set.
    Collections.addAll(ignoredActivityClasses, activityClasses);
  }
}
