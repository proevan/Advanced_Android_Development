/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.android.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    public final String LOG_TAG = MyWatchFace.class.getSimpleName();
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHandPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        Paint mTextPaint;
        Paint mTextDatePaint;
        Paint mTextTempPaint;

        float mTextDateYOffSet;
        float mSeparatedLineYOffSet;
        float mTextTempYOffSet;

        SimpleDateFormat mDateFormat;
        Calendar mCalendar;

        float mXOffset;
        float mYOffset;

        float mSeparatedLineWidth;

        private GoogleApiClient mGoogleApiClient;

        private static final String SUNSHINE_WEAR_DATA_PATH = "/sunshine_weather_data";
        private static final String DATA_MAP_KEY_WEATHER_ID = "DATA_MAP_KEY_WEATHER_ID";
        private static final String DATA_MAP_KEY_MAX_TEMP = "DATA_MAP_KEY_MAX_TEMP";
        private static final String DATA_MAP_KEY_MIN_TEMP = "DATA_MAP_KEY_MIN_TEMP";

        int mWeatherId;
        String mMinTemp;
        String mMaxTemp;
        int mWeatherIconRes;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(LOG_TAG, "Engine onCreate");

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = MyWatchFace.this.getResources();

            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mTextDateYOffSet = resources.getDimension(R.dimen.digital_text_date_y_offset);
            mSeparatedLineYOffSet = resources.getDimension(R.dimen.digital_separated_line_y_offset);
            mTextTempYOffSet = resources.getDimension(R.dimen.digital_text_temp_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.digital_background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            mTextDatePaint = new Paint();
            mTextDatePaint = createTextPaint(resources.getColor(R.color.digital_text_date));

            mSeparatedLineWidth = resources.getDimension(R.dimen.digital_separated_line_width);

            mTextTempPaint = new Paint();
            mTextTempPaint = createTextPaint(resources.getColor(R.color.digital_text_temperature));

            mCalendar = Calendar.getInstance();
            initFormats();
            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(getBaseContext())
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            if (!mGoogleApiClient.isConnected()) {
                mGoogleApiClient.connect();
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(LOG_TAG, "onConnected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(LOG_TAG, "onDataChanged");
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(SUNSHINE_WEAR_DATA_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        if (dataMap.containsKey(DATA_MAP_KEY_WEATHER_ID)) {
                            mWeatherId = dataMap.getInt(DATA_MAP_KEY_WEATHER_ID);
                            mWeatherIconRes = Utility.getIconResourceForWeatherCondition(mWeatherId);
                        }
                        if (dataMap.containsKey(DATA_MAP_KEY_MIN_TEMP)) {
                            mMinTemp = dataMap.getString(DATA_MAP_KEY_MIN_TEMP);
                        }
                        if (dataMap.containsKey(DATA_MAP_KEY_MAX_TEMP)) {
                            mMaxTemp = dataMap.getString(DATA_MAP_KEY_MAX_TEMP);
                        }
                        Log.d(LOG_TAG, String.format("weatherId: %d ,  minTemp: %s , minTemp: %s", mWeatherId, mMinTemp, mMaxTemp));
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(LOG_TAG, "onConnectionSuspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(LOG_TAG, "onConnectionFailed errorCode: " + connectionResult.getErrorCode());
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = MyWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            String timeText = getTimeText();
            float widthTimeText = mTextPaint.measureText(timeText);
            float xOffsetTimeText = (bounds.width() - widthTimeText) / 2;
            canvas.drawText(timeText, xOffsetTimeText, mYOffset, mTextPaint);

            String dateText = getDateText();
            float widthDateText = mTextDatePaint.measureText(dateText);
            float xOffsetDateText = (bounds.width() - widthDateText) / 2;
            canvas.drawText(dateText, xOffsetDateText, mYOffset + mTextDateYOffSet, mTextDatePaint);

            float xOffsetSeparatedLine = (bounds.width() - mSeparatedLineWidth) / 2;
            canvas.drawLine(xOffsetSeparatedLine, mYOffset + mSeparatedLineYOffSet, xOffsetSeparatedLine + mSeparatedLineWidth, mYOffset + mSeparatedLineYOffSet, mTextDatePaint);

            String temperatureText = "loading...";
            if (mMinTemp != null && mMaxTemp != null) {
                temperatureText = mMinTemp + " " + mMaxTemp;
            }
            float widthTempText = mTextTempPaint.measureText(temperatureText);
            float xOffsetTempText = (bounds.width() - widthTempText) / 2;
            canvas.drawText(temperatureText, xOffsetTempText, mYOffset + mTextTempYOffSet, mTextTempPaint);

            if (mWeatherIconRes > 0) {
                Bitmap bitmap = Utility.getBitmapFromResource(getApplicationContext(), mWeatherIconRes);
                canvas.drawBitmap(bitmap, bounds.width() - xOffsetTempText, mYOffset + mTextTempYOffSet - (int) (bitmap.getHeight() / 1.5), null);
            }
        }

        private String getTimeText() {
            return String.format("%02d:%02d", mTime.hour, mTime.minute);
        }

        private String getDateText() {
            return mDateFormat.format(mCalendar.getTime());
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float textDateSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_date_size_round : R.dimen.digital_text_date_size);
            float textTempSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_temp_size_round : R.dimen.digital_text_temp_size);

            mTextPaint.setTextSize(textSize);
            mTextDatePaint.setTextSize(textDateSize);
            mTextTempPaint.setTextSize(textTempSize);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
