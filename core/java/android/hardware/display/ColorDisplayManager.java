/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.display;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.metrics.LogMaker;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.LocalTime;

/**
 * Manages the display's color transforms and modes.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.COLOR_DISPLAY_SERVICE)
public final class ColorDisplayManager {

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({CAPABILITY_NONE, CAPABILITY_PROTECTED_CONTENT, CAPABILITY_HARDWARE_ACCELERATION_GLOBAL,
            CAPABILITY_HARDWARE_ACCELERATION_PER_APP})
    public @interface CapabilityType {}

    /**
     * The device does not support color transforms.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_NONE = 0x0;
    /**
     * The device can properly apply transforms over protected content.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_PROTECTED_CONTENT = 0x1;
    /**
     * The device's hardware can efficiently apply transforms to the entire display.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_HARDWARE_ACCELERATION_GLOBAL = 0x2;
    /**
     * The device's hardware can efficiently apply transforms to a specific Surface (window) so
     * that apps can be transformed independently of one another.
     *
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_HARDWARE_ACCELERATION_PER_APP = 0x4;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({ AUTO_MODE_DISABLED, AUTO_MODE_CUSTOM_TIME, AUTO_MODE_TWILIGHT })
    public @interface AutoMode {}

    /**
     * Auto mode value to prevent Night display from being automatically activated. It can still
     * be activated manually via {@link #setNightDisplayActivated(boolean)}.
     *
     * @see #setNightDisplayAutoMode(int)
     *
     * @hide
     */
    @SystemApi
    public static final int AUTO_MODE_DISABLED = 0;
    /**
     * Auto mode value to automatically activate Night display at a specific start and end time.
     *
     * @see #setNightDisplayAutoMode(int)
     * @see #setNightDisplayCustomStartTime(LocalTime)
     * @see #setNightDisplayCustomEndTime(LocalTime)
     *
     * @hide
     */
    @SystemApi
    public static final int AUTO_MODE_CUSTOM_TIME = 1;
    /**
     * Auto mode value to automatically activate Night display from sunset to sunrise.
     *
     * @see #setNightDisplayAutoMode(int)
     *
     * @hide
     */
    @SystemApi
    public static final int AUTO_MODE_TWILIGHT = 2;

    private final ColorDisplayManagerInternal mManager;
    private MetricsLogger mMetricsLogger;

    /**
     * @hide
     */
    public ColorDisplayManager() {
        mManager = ColorDisplayManagerInternal.getInstance();
    }

    /**
     * (De)activates the night display transform.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayActivated(boolean activated) {
        return mManager.setNightDisplayActivated(activated);
    }

    /**
     * Returns whether the night display transform is currently active.
     *
     * @hide
     */
    public boolean isNightDisplayActivated() {
        return mManager.isNightDisplayActivated();
    }

    /**
     * Sets the color temperature of the night display transform.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayColorTemperature(int temperature) {
        return mManager.setNightDisplayColorTemperature(temperature);
    }

    /**
     * Gets the color temperature of the night display transform.
     *
     * @hide
     */
    public int getNightDisplayColorTemperature() {
        return mManager.getNightDisplayColorTemperature();
    }

    /**
     * Returns the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link #AUTO_MODE_DISABLED}, {@link #AUTO_MODE_CUSTOM_TIME}, or
     * {@link #AUTO_MODE_TWILIGHT}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public @AutoMode int getNightDisplayAutoMode() {
        return mManager.getNightDisplayAutoMode();
    }

    /**
     * Returns the current auto mode value, without validation, or {@code 1} if the auto mode has
     * never been set.
     *
     * @hide
     */
    public int getNightDisplayAutoModeRaw() {
        return mManager.getNightDisplayAutoModeRaw();
    }

    /**
     * Sets the current auto mode value controlling when Night display will be automatically
     * activated. One of {@link #AUTO_MODE_DISABLED}, {@link #AUTO_MODE_CUSTOM_TIME}, or
     * {@link #AUTO_MODE_TWILIGHT}.
     *
     * @param autoMode the new auto mode to use
     * @return {@code true} if new auto mode was set successfully
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayAutoMode(@AutoMode int autoMode) {
        if (autoMode != AUTO_MODE_DISABLED
                && autoMode != AUTO_MODE_CUSTOM_TIME
                && autoMode != AUTO_MODE_TWILIGHT) {
            throw new IllegalArgumentException("Invalid autoMode: " + autoMode);
        }
        if (mManager.getNightDisplayAutoMode() != autoMode) {
            getMetricsLogger().write(new LogMaker(
                    MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CHANGED)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(autoMode));
        }
        return mManager.setNightDisplayAutoMode(autoMode);
    }

    /**
     * Returns the local time when Night display will be automatically activated when using
     * {@link ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     *
     * @hide
     */
    public @NonNull LocalTime getNightDisplayCustomStartTime() {
        return mManager.getNightDisplayCustomStartTime().getLocalTime();
    }

    /**
     * Sets the local time when Night display will be automatically activated when using
     * {@link ColorDisplayManager#AUTO_MODE_CUSTOM_TIME}.
     *
     * @param startTime the local time to automatically activate Night display
     * @return {@code true} if the new custom start time was set successfully
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayCustomStartTime(@NonNull LocalTime startTime) {
        if (startTime == null) {
            throw new IllegalArgumentException("startTime cannot be null");
        }
        getMetricsLogger().write(new LogMaker(
                MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CUSTOM_TIME_CHANGED)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(0));
        return mManager.setNightDisplayCustomStartTime(new Time(startTime));
    }

    /**
     * Returns the local time when Night display will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM_TIME}.
     *
     * @hide
     */
    public @NonNull LocalTime getNightDisplayCustomEndTime() {
        return mManager.getNightDisplayCustomEndTime().getLocalTime();
    }

    /**
     * Sets the local time when Night display will be automatically deactivated when using
     * {@link #AUTO_MODE_CUSTOM_TIME}.
     *
     * @param endTime the local time to automatically deactivate Night display
     * @return {@code true} if the new custom end time was set successfully
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setNightDisplayCustomEndTime(@NonNull LocalTime endTime) {
        if (endTime == null) {
            throw new IllegalArgumentException("endTime cannot be null");
        }
        getMetricsLogger().write(new LogMaker(
                MetricsEvent.ACTION_NIGHT_DISPLAY_AUTO_MODE_CUSTOM_TIME_CHANGED)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(1));
        return mManager.setNightDisplayCustomEndTime(new Time(endTime));
    }

    /**
     * Returns whether the device has a wide color gamut display.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean isDeviceColorManaged() {
        return mManager.isDeviceColorManaged();
    }

    /**
     * Set the level of color saturation to apply to the display.
     *
     * @param saturationLevel 0-100 (inclusive), where 100 is full saturation
     * @return whether the saturation level change was applied successfully
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setSaturationLevel(@IntRange(from = 0, to = 100) int saturationLevel) {
        return mManager.setSaturationLevel(saturationLevel);
    }

    /**
     * Set the level of color saturation to apply to a specific app.
     *
     * @param packageName the package name of the app whose windows should be desaturated
     * @param saturationLevel 0-100 (inclusive), where 100 is full saturation
     * @return whether the saturation level change was applied successfully
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setAppSaturationLevel(@NonNull String packageName,
            @IntRange(from = 0, to = 100) int saturationLevel) {
        return mManager.setAppSaturationLevel(packageName, saturationLevel);
    }

    /**
     * Returns {@code true} if Night Display is supported by the device.
     *
     * @hide
     */
    public static boolean isNightDisplayAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_nightDisplayAvailable);
    }

    /**
     * Returns the minimum allowed color temperature (in Kelvin) to tint the display when
     * activated.
     *
     * @hide
     */
    public static int getMinimumColorTemperature(Context context) {
        return context.getResources()
                .getInteger(R.integer.config_nightDisplayColorTemperatureMin);
    }

    /**
     * Returns the maximum allowed color temperature (in Kelvin) to tint the display when
     * activated.
     *
     * @hide
     */
    public static int getMaximumColorTemperature(Context context) {
        return context.getResources()
                .getInteger(R.integer.config_nightDisplayColorTemperatureMax);
    }

    /**
     * Returns {@code true} if display white balance is supported by the device.
     *
     * @hide
     */
    public static boolean isDisplayWhiteBalanceAvailable(Context context) {
        return context.getResources().getBoolean(R.bool.config_displayWhiteBalanceAvailable);
    }

    /**
     * Check if the color transforms are color accelerated. Some transforms are experimental only
     * on non-accelerated platforms due to the performance implications.
     *
     * @hide
     */
    public static boolean isColorTransformAccelerated(Context context) {
        return context.getResources().getBoolean(R.bool.config_setColorTransformAccelerated);
    }

    /**
     * Returns the available software and hardware color transform capabilities of this device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public @CapabilityType int getTransformCapabilities() {
        return mManager.getTransformCapabilities();
    }

    private MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    private static class ColorDisplayManagerInternal {

        private static ColorDisplayManagerInternal sInstance;

        private final IColorDisplayManager mCdm;

        private ColorDisplayManagerInternal(IColorDisplayManager colorDisplayManager) {
            mCdm = colorDisplayManager;
        }

        public static ColorDisplayManagerInternal getInstance() {
            synchronized (ColorDisplayManagerInternal.class) {
                if (sInstance == null) {
                    try {
                        IBinder b = ServiceManager.getServiceOrThrow(Context.COLOR_DISPLAY_SERVICE);
                        sInstance = new ColorDisplayManagerInternal(
                                IColorDisplayManager.Stub.asInterface(b));
                    } catch (ServiceNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return sInstance;
            }
        }

        boolean isNightDisplayActivated() {
            try {
                return mCdm.isNightDisplayActivated();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayActivated(boolean activated) {
            try {
                return mCdm.setNightDisplayActivated(activated);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getNightDisplayColorTemperature() {
            try {
                return mCdm.getNightDisplayColorTemperature();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayColorTemperature(int temperature) {
            try {
                return mCdm.setNightDisplayColorTemperature(temperature);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getNightDisplayAutoMode() {
            try {
                return mCdm.getNightDisplayAutoMode();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getNightDisplayAutoModeRaw() {
            try {
                return mCdm.getNightDisplayAutoModeRaw();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayAutoMode(int autoMode) {
            try {
                return mCdm.setNightDisplayAutoMode(autoMode);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        Time getNightDisplayCustomStartTime() {
            try {
                return mCdm.getNightDisplayCustomStartTime();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayCustomStartTime(Time startTime) {
            try {
                return mCdm.setNightDisplayCustomStartTime(startTime);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        Time getNightDisplayCustomEndTime() {
            try {
                return mCdm.getNightDisplayCustomEndTime();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setNightDisplayCustomEndTime(Time endTime) {
            try {
                return mCdm.setNightDisplayCustomEndTime(endTime);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean isDeviceColorManaged() {
            try {
                return mCdm.isDeviceColorManaged();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setSaturationLevel(int saturationLevel) {
            try {
                return mCdm.setSaturationLevel(saturationLevel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        boolean setAppSaturationLevel(String packageName, int saturationLevel) {
            try {
                return mCdm.setAppSaturationLevel(packageName, saturationLevel);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        int getTransformCapabilities() {
            try {
                return mCdm.getTransformCapabilities();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
