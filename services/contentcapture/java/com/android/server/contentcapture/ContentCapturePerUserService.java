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

package com.android.server.contentcapture;

import static android.service.contentcapture.ContentCaptureService.setClientState;

import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_CONTENT;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_DATA;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.contentcapture.SnapshotData;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.contentcapture.ContentCaptureSession;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Per-user instance of {@link ContentCaptureManagerService}.
 */
final class ContentCapturePerUserService
        extends
        AbstractPerUserSystemService<ContentCapturePerUserService, ContentCaptureManagerService> {

    private static final String TAG = ContentCaptureManagerService.class.getSimpleName();

    @GuardedBy("mLock")
    private final ArrayMap<String, ContentCaptureServerSession> mSessions =
            new ArrayMap<>();

    // TODO(b/111276913): add mechanism to prune stale sessions, similar to Autofill's

    protected ContentCapturePerUserService(
            ContentCaptureManagerService master, Object lock, @UserIdInt int userId) {
        super(master, new FrameworkResourcesServiceNameResolver(master.getContext(), userId, lock,
                com.android.internal.R.string.config_defaultContentCaptureService), lock, userId);
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfo(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {

        int flags = PackageManager.GET_META_DATA;
        final boolean isTemp = isTemporaryServiceSetLocked();
        if (!isTemp) {
            flags |= PackageManager.MATCH_SYSTEM_ONLY;
        }

        ServiceInfo si;
        try {
            si = AppGlobals.getPackageManager().getServiceInfo(serviceComponent, flags, mUserId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not get service for " + serviceComponent + ": " + e);
            return null;
        }
        if (si == null) {
            Slog.w(TAG, "Could not get serviceInfo for " + (isTemp ? " (temp)" : "(default system)")
                    + " " + serviceComponent.flattenToShortString());
            return null;
        }
        if (!Manifest.permission.BIND_CONTENT_CAPTURE_SERVICE.equals(si.permission)) {
            Slog.w(TAG, "ContentCaptureService from '" + si.packageName
                    + "' does not require permission "
                    + Manifest.permission.BIND_CONTENT_CAPTURE_SERVICE);
            throw new SecurityException("Service does not require permission "
                    + Manifest.permission.BIND_CONTENT_CAPTURE_SERVICE);
        }
        return si;
    }

    @Override // from PerUserSystemService
    @GuardedBy("mLock")
    protected boolean updateLocked(boolean disabled) {
        destroyLocked();
        return super.updateLocked(disabled);
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void startSessionLocked(@NonNull IBinder activityToken,
            @NonNull ComponentName componentName, int taskId, int displayId,
            @NonNull String sessionId, int uid, int flags, boolean bindInstantServiceAllowed,
            @NonNull IResultReceiver clientReceiver) {
        if (!isEnabledLocked()) {
            setClientState(clientReceiver, ContentCaptureSession.STATE_DISABLED, /* binder=*/ null);
            return;
        }
        final ComponentName serviceComponentName = getServiceComponentName();
        if (serviceComponentName == null) {
            // TODO(b/111276913): this happens when the system service is starting, we should
            // probably handle it in a more elegant way (like waiting for boot_complete or
            // something like that
            if (mMaster.debug) {
                Slog.d(TAG, "startSession(" + activityToken + "): hold your horses");
            }
            return;
        }

        final ContentCaptureServerSession existingSession = mSessions.get(sessionId);
        if (existingSession != null) {
            Slog.w(TAG, "startSession(id=" + existingSession + ", token=" + activityToken
                    + ": ignoring because it already exists for " + existingSession.mActivityToken);
            setClientState(clientReceiver, ContentCaptureSession.STATE_DISABLED_DUPLICATED_ID,
                    /* binder=*/ null);
            return;
        }

        final ContentCaptureServerSession newSession = new ContentCaptureServerSession(getContext(),
                mUserId, mLock, activityToken, this, serviceComponentName, componentName, taskId,
                displayId, sessionId, uid, flags, bindInstantServiceAllowed,
                mMaster.verbose);
        if (mMaster.verbose) {
            Slog.v(TAG, "startSession(): new session for "
                    + ComponentName.flattenToShortString(componentName) + " and id " + sessionId);
        }
        mSessions.put(sessionId, newSession);
        newSession.notifySessionStartedLocked(clientReceiver);
    }

    // TODO(b/111276913): log metrics
    @GuardedBy("mLock")
    public void finishSessionLocked(@NonNull String sessionId) {
        if (!isEnabledLocked()) {
            return;
        }

        final ContentCaptureServerSession session = mSessions.get(sessionId);
        if (session == null) {
            if (mMaster.debug) {
                Slog.d(TAG, "finishSession(): no session with id" + sessionId);
            }
            return;
        }
        if (mMaster.verbose) Slog.v(TAG, "finishSession(): id=" + sessionId);
        session.removeSelfLocked(/* notifyRemoteService= */ true);
    }

    @GuardedBy("mLock")
    public boolean sendActivityAssistDataLocked(@NonNull IBinder activityToken,
            @NonNull Bundle data) {
        final String id = getSessionId(activityToken);
        if (id != null) {
            final ContentCaptureServerSession session = mSessions.get(id);
            final Bundle assistData = data.getBundle(ASSIST_KEY_DATA);
            final AssistStructure assistStructure = data.getParcelable(ASSIST_KEY_STRUCTURE);
            final AssistContent assistContent = data.getParcelable(ASSIST_KEY_CONTENT);
            final SnapshotData snapshotData = new SnapshotData(assistData,
                    assistStructure, assistContent);
            session.sendActivitySnapshotLocked(snapshotData);
            return true;
        } else {
            Slog.e(TAG, "Failed to notify activity assist data for activity: " + activityToken);
        }
        return false;
    }

    @GuardedBy("mLock")
    public void removeSessionLocked(@NonNull String sessionId) {
        mSessions.remove(sessionId);
    }

    @GuardedBy("mLock")
    public boolean isContentCaptureServiceForUserLocked(int uid) {
        return uid == getServiceUidLocked();
    }

    @GuardedBy("mLock")
    private ContentCaptureServerSession getSession(@NonNull IBinder activityToken) {
        for (int i = 0; i < mSessions.size(); i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            if (session.mActivityToken.equals(activityToken)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Destroys the service and all state associated with it.
     *
     * <p>Called when the service was disabled (for example, if the settings change).
     */
    @GuardedBy("mLock")
    public void destroyLocked() {
        if (mMaster.debug) Slog.d(TAG, "destroyLocked()");
        destroySessionsLocked();
    }

    @GuardedBy("mLock")
    void destroySessionsLocked() {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            session.destroyLocked(true);
        }
        mSessions.clear();
    }

    @GuardedBy("mLock")
    void listSessionsLocked(ArrayList<String> output) {
        final int numSessions = mSessions.size();
        for (int i = 0; i < numSessions; i++) {
            final ContentCaptureServerSession session = mSessions.valueAt(i);
            output.add(session.toShortString());
        }
    }

    @Override
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);
        if (mSessions.isEmpty()) {
            pw.print(prefix); pw.println("no sessions");
        } else {
            final int size = mSessions.size();
            pw.print(prefix); pw.print("number sessions: "); pw.println(size);
            final String prefix2 = prefix + "  ";
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print("session@"); pw.println(i);
                final ContentCaptureServerSession session = mSessions.valueAt(i);
                session.dumpLocked(prefix2, pw);
            }
        }
    }

    /**
     * Returns the session id associated with the given activity.
     */
    @GuardedBy("mLock")
    private String getSessionId(@NonNull IBinder activityToken) {
        for (int i = 0; i < mSessions.size(); i++) {
            ContentCaptureServerSession session = mSessions.valueAt(i);
            if (session.isActivitySession(activityToken)) {
                return mSessions.keyAt(i);
            }
        }
        return null;
    }
}
