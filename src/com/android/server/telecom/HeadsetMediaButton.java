/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telecom.Log;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;

/* Unisoc FL0108020022: Add for double press handset media button feature. @{ */
import android.telephony.CarrierConfigManager;
import android.telephony.CarrierConfigManagerEx;
/* @} */

/**
 * Static class to handle listening to the headset media buttons.
 */
public class HeadsetMediaButton extends CallsManagerListenerBase {

    // Types of media button presses
    @VisibleForTesting
    public static final int SHORT_PRESS = 1;
    @VisibleForTesting
    public static final int LONG_PRESS = 2;
    @VisibleForTesting
    public static final int DOUBLE_PRESS = 3; //Unisoc FL0108020022: Add for double press handset media button feature.

    private static final AudioAttributes AUDIO_ATTRIBUTES = new AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build();

    private static final int MSG_MEDIA_SESSION_INITIALIZE = 0;
    private static final int MSG_MEDIA_SESSION_SET_ACTIVE = 1;

    private final MediaSession.Callback mSessionCallback = new MediaSession.Callback() {
        @Override
        public boolean onMediaButtonEvent(Intent intent) {
            try {
                Log.startSession("HMB.oMBE");
                KeyEvent event = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                Log.v(this, "SessionCallback.onMediaButton()...  event = %s.", event);
                if ((event != null) && ((event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK) ||
                        (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))) {
                    synchronized (mLock) {
                        Log.v(this, "SessionCallback: HEADSETHOOK/MEDIA_PLAY_PAUSE");
                        boolean consumed = handleCallMediaButton(event);
                        Log.v(this, "==> handleCallMediaButton(): consumed = %b.", consumed);
                        return consumed;
                    }
                }
                return true;
            } finally {
                Log.endSession();
            }
        }
    };

    private final Handler mMediaSessionHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_MEDIA_SESSION_INITIALIZE: {
                    MediaSession session = new MediaSession(
                            mContext,
                            HeadsetMediaButton.class.getSimpleName());
                    session.setCallback(mSessionCallback);
                    session.setFlags(MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY
                            | MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
                    session.setPlaybackToLocal(AUDIO_ATTRIBUTES);
                    mSession = session;
                    break;
                }
                case MSG_MEDIA_SESSION_SET_ACTIVE: {
                    if (mSession != null) {
                        boolean activate = msg.arg1 != 0;
                        if (activate != mSession.isActive()) {
                            mSession.setActive(activate);
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        }
    };

    private final Context mContext;
    private final CallsManager mCallsManager;
    private final TelecomSystem.SyncRoot mLock;
    private MediaSession mSession;
    private KeyEvent mLastHookEvent;
    /* Unisoc FL0108020022: Add for double press handset media button feature. @{ */
    private static final long DOUBLE_PRESS_TIME = 500;
    private Boolean mIsDoublePress = false;
    private CheckDoublePress mPendingCheckDoublePress = null;
    Handler mHandler = new Handler();
    /* @} */

    public HeadsetMediaButton(
            Context context,
            CallsManager callsManager,
            TelecomSystem.SyncRoot lock) {
        mContext = context;
        mCallsManager = callsManager;
        mLock = lock;

        // Create a MediaSession but don't enable it yet. This is a
        // replacement for MediaButtonReceiver
        mMediaSessionHandler.obtainMessage(MSG_MEDIA_SESSION_INITIALIZE).sendToTarget();
    }

    /**
     * Handles the wired headset button while in-call.
     *
     * @return true if we consumed the event.
     */
    private boolean handleCallMediaButton(KeyEvent event) {
        Log.d(this, "handleCallMediaButton()...%s %s", event.getAction(), event.getRepeatCount());

        // Save ACTION_DOWN Event temporarily.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mLastHookEvent = event;
        }

        if (event.isLongPress()) {
            return mCallsManager.onMediaButton(LONG_PRESS);
        } else if ((event.getAction() == KeyEvent.ACTION_UP)
                    // Add for bug895699 : avoid key up event treat as short click
                    && (mLastHookEvent != null) && (!mLastHookEvent.isLongPress()) ) {
            // We should not judge SHORT_PRESS by ACTION_UP event repeatCount, because it always
            // return 0.
            // Actually ACTION_DOWN event repeatCount only increases when LONG_PRESS performed.
            /* Unisoc FL0108020022: Add for double press handset media button feature. @{*/
            if (isSupportDoublePressOnHeadsetKey()) {
                if (!mIsDoublePress) {
                    mIsDoublePress = true;
                    if (mPendingCheckDoublePress == null) {
                        mPendingCheckDoublePress = new CheckDoublePress();
                    }
                    mHandler.postDelayed(mPendingCheckDoublePress, DOUBLE_PRESS_TIME);
                } else {
                    // press twice within 500 millisecond means double press.
                    mIsDoublePress = false;
                    doublePress();
                }
            } else if (mLastHookEvent != null && mLastHookEvent.getRepeatCount() == 0) {
                return mCallsManager.onMediaButton(SHORT_PRESS);
            }
            /* @} */
        }

        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            mLastHookEvent = null;
        }

        return true;
    }

    /** ${inheritDoc} */
    @Override
    public void onCallAdded(Call call) {
        if (call.isExternalCall()) {
            return;
        }
        mMediaSessionHandler.obtainMessage(MSG_MEDIA_SESSION_SET_ACTIVE, 1, 0).sendToTarget();
    }

    /** ${inheritDoc} */
    @Override
    public void onCallRemoved(Call call) {
        if (call.isExternalCall()) {
            return;
        }
        if (!mCallsManager.hasAnyCalls()) {
            mMediaSessionHandler.obtainMessage(MSG_MEDIA_SESSION_SET_ACTIVE, 0, 0).sendToTarget();
        }
    }

    /** ${inheritDoc} */
    @Override
    public void onExternalCallChanged(Call call, boolean isExternalCall) {
        if (isExternalCall) {
            onCallRemoved(call);
        } else {
            onCallAdded(call);
        }
    }

    /* Unisoc FL0108020022: Add for double press handset media button feature. @{ */
    private boolean isSupportDoublePressOnHeadsetKey() {
        CarrierConfigManager configManager = (CarrierConfigManager)
                mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        if (configManager.getConfigForDefaultPhone() != null) {
            return configManager.getConfigForDefaultPhone().getBoolean(
                    CarrierConfigManagerEx.KEY_FEATURE_DOUBLE_PRESS_ON_HEADSET_KEY_BOOL);
        }
        return false;
    }

    private void singlePress() {
        Log.d(this, "singlePress...");
        if (mLastHookEvent != null && mLastHookEvent.getRepeatCount() > 0) {
            Log.d(this, "last hook event repeatCount bigger than 0.");
            return;
        }
        mCallsManager.onMediaButton(SHORT_PRESS);
        mLastHookEvent = null;
    }

    private void doublePress() {
        Log.d(this, "doublePress...");
        mCallsManager.onMediaButton(DOUBLE_PRESS);
    }

    class CheckDoublePress implements java.lang.Runnable {

        public void run() {
            if (mIsDoublePress) {
                singlePress();
            }
            mIsDoublePress = false;
        }
    }
    /* @} */
}
