/*
 * Copyright (c) 2010, MoPub Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'MoPub Inc.' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.mopub.mobileads;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings.Secure;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.mopub.mobileads.MoPubView.LocationAwareness;

public class AdView extends WebView {
    public static final String AD_ORIENTATION_PORTRAIT_ONLY = "p";
    public static final String AD_ORIENTATION_LANDSCAPE_ONLY = "l";
    public static final String AD_ORIENTATION_BOTH = "b";
    public static final String DEVICE_ORIENTATION_PORTRAIT = "p";
    public static final String DEVICE_ORIENTATION_LANDSCAPE = "l";
    public static final String DEVICE_ORIENTATION_SQUARE = "s";
    public static final String DEVICE_ORIENTATION_UNKNOWN = "u";
    public static final String EXTRA_AD_CLICK_DATA = "com.mopub.intent.extra.AD_CLICK_DATA";
    
    private static final int MINIMUM_REFRESH_TIME_MILLISECONDS = 10000;
    private static final FrameLayout.LayoutParams WRAP_AND_CENTER_LAYOUT_PARAMS =
            new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER);
    
    private String mAdUnitId;
    private String mKeywords;
    private String mUrl;
    private String mClickthroughUrl;
    private String mRedirectUrl;
    private String mFailUrl;
    private String mImpressionUrl;
    private Location mLocation;
    private boolean mIsLoading;
    private boolean mAutorefreshEnabled;
    private boolean mTesting;
    private int mRefreshTimeMilliseconds = 60000;
    private int mWidth;
    private int mHeight;
    private String mAdOrientation;
    private Map<String, Object> mLocalExtras = new HashMap<String, Object>();

    protected MoPubView mMoPubView;
    private String mResponseString;
    private String mUserAgent;
    private boolean mIsDestroyed;
    private final Handler mHandler = new Handler();
    private AdFetcher mAdFetcher;

    public AdView(Context context, MoPubView view) {
        /* Important: don't allow any WebView subclass to be instantiated using
         * an Activity context, as it will leak on Froyo devices and earlier.
         */
        super(context.getApplicationContext());
        
        mMoPubView = view;
        mAutorefreshEnabled = true;
        
        /* Store user agent string at beginning to prevent NPE during background
         * thread operations.
         */
        mUserAgent = getSettings().getUserAgentString();
        mAdFetcher = new AdFetcher(this, mUserAgent);
        
        disableScrollingAndZoom();
        getSettings().setJavaScriptEnabled(true);
        getSettings().setPluginsEnabled(true);
        setBackgroundColor(Color.TRANSPARENT);
        setWebViewClient(new AdWebViewClient());
        
        addMoPubUriJavascriptInterface();
    }
    
    private void disableScrollingAndZoom() {
        setHorizontalScrollBarEnabled(false);
        setHorizontalScrollbarOverlay(false);
        setVerticalScrollBarEnabled(false);
        setVerticalScrollbarOverlay(false);
        getSettings().setSupportZoom(false);
    }
    
    /* XXX (2/15/12): This is a workaround for a problem on ICS devices where
     * WebViews with layout height WRAP_CONTENT can mysteriously render with
     * zero height. This seems to happen when calling loadData() with HTML that
     * sets window.location during its "onload" event. We use loadData() when
     * displaying interstitials, and our creatives use window.location to
     * communicate ad loading status to AdViews. This results in zero-height
     * interstitials. We counteract this by using a Javascript interface object
     * to signal loading status, rather than modifying window.location.
     */
    private void addMoPubUriJavascriptInterface() {
        
        final class MoPubUriJavascriptInterface {
            // This method appears to be unused, since it will only be called from JavaScript.
            @SuppressWarnings("unused")
            public boolean fireFinishLoad() {
                AdView.this.postHandlerRunnable(new Runnable() {
                    @Override
                    public void run() {
                        AdView.this.adDidLoad();
                    }
                });
                return true;
            }
        }
        
        addJavascriptInterface(new MoPubUriJavascriptInterface(), "mopubUriInterface");
    }
    
    private void postHandlerRunnable(Runnable r) {
        mHandler.post(r);
    }
    
    private class AdWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            AdView adView = (AdView) view;
            
            // Handle the special mopub:// scheme calls.
            if (url.startsWith("mopub://")) {
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                
                if (host.equals("finishLoad")) adView.adDidLoad();
                else if (host.equals("close")) adView.adDidClose();
                else if (host.equals("failLoad")) adView.loadFailUrl(MoPubErrorCode.UNSPECIFIED);
                else if (host.equals("custom")) adView.handleCustomIntentFromUri(uri);
                return true;
            }
            // Handle other phone intents.
            else if (url.startsWith("tel:") || url.startsWith("voicemail:") ||
                    url.startsWith("sms:") || url.startsWith("mailto:") ||
                    url.startsWith("geo:") || url.startsWith("google.streetview:")) { 
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    getContext().startActivity(intent);
                    registerClick();
                } catch (ActivityNotFoundException e) {
                    Log.w("MoPub", "Could not handle intent with URI: " + url +
                        ". Is this intent unsupported on your phone?");
                }
                return true;
            }
            // Fast fail if market:// intent is called when Google Play is not installed
            else if (url.startsWith("market://")) {
                // Determine which activities can handle the market intent
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                PackageManager packageManager = getContext().getPackageManager();
                List<ResolveInfo> activities = packageManager.queryIntentActivities(intent, 0);
                
                // If there are no relevant activities, don't follow the link
                boolean isIntentSafe = activities.size() > 0;
                if (!isIntentSafe) {
                    Log.w("MoPub", "Could not handle market action: " + url
                            + ". Perhaps you're running in the emulator, which does not have "
                            + "the Android Market?");
                    return true;
                }
            }

            url = urlWithClickTrackingRedirect(adView, url);
            Log.d("MoPub", "Ad clicked. Click URL: " + url);
            mMoPubView.adClicked();

            showBrowserForUrl(url);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // If the URL being loaded shares the redirectUrl prefix, open it in the browser.
            AdView adView = (AdView) view;
            String redirectUrl = adView.getRedirectUrl();
            if (redirectUrl != null && url.startsWith(redirectUrl)) {
                url = urlWithClickTrackingRedirect(adView, url);
                view.stopLoading();
                showBrowserForUrl(url);
            }
        }
        
        private String urlWithClickTrackingRedirect(AdView adView, String url) {
            String clickthroughUrl = adView.getClickthroughUrl();
            if (clickthroughUrl == null) return url;
            else {
                String encodedUrl = Uri.encode(url);
                return clickthroughUrl + "&r=" + encodedUrl;
            }
        }
    }
    
    public void loadAd() {
        if (mAdUnitId == null) {
            Log.d("MoPub", "Can't load an ad in this ad view because the ad unit ID is null. " + 
                    "Did you forget to call setAdUnitId()?");
            return;
        }

        if (!isNetworkAvailable()) {
            Log.d("MoPub", "Can't load an ad because there is no network connectivity.");
            scheduleRefreshTimerIfEnabled();
            return;
        }

        if (mLocation == null) mLocation = getLastKnownLocation();

        String adUrl = generateAdUrl();
        loadUrl(adUrl);
    }

    private boolean isNetworkAvailable() {
        Context context = getContext();
        
        // If we don't have network state access, just assume the network is up.
        String permission = android.Manifest.permission.ACCESS_NETWORK_STATE;
        int result = context.checkCallingPermission(permission);
        if (result == PackageManager.PERMISSION_DENIED) return true;
        
        // Otherwise, perform the connectivity check.
        ConnectivityManager cm
                = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }
    
    /*
     * Returns the last known location of the device using its GPS and network location providers.
     * May be null if: 
     * - Location permissions are not requested in the Android manifest file
     * - The location providers don't exist
     * - Location awareness is disabled in the parent MoPubView
     */
    private Location getLastKnownLocation() {
        LocationAwareness locationAwareness = mMoPubView.getLocationAwareness();
        int locationPrecision = mMoPubView.getLocationPrecision();
        Location result = null;
        
        if (locationAwareness == LocationAwareness.LOCATION_AWARENESS_DISABLED) {
            return null;
        }
        
        LocationManager lm 
                = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        Location gpsLocation = null;
        try {
            gpsLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            Log.d("MoPub", "Failed to retrieve GPS location: access appears to be disabled.");
        } catch (IllegalArgumentException e) {
            Log.d("MoPub", "Failed to retrieve GPS location: device has no GPS provider.");
        }
        
        Location networkLocation = null;
        try {
            networkLocation = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        } catch (SecurityException e) {
            Log.d("MoPub", "Failed to retrieve network location: access appears to be disabled.");
        } catch (IllegalArgumentException e) {
            Log.d("MoPub", "Failed to retrieve network location: device has no network provider.");
        }
        
        if (gpsLocation == null && networkLocation == null) {
            return null;
        }
        else if (gpsLocation != null && networkLocation != null) {
            if (gpsLocation.getTime() > networkLocation.getTime()) result = gpsLocation;
            else result = networkLocation;
        }
        else if (gpsLocation != null) result = gpsLocation;
        else result = networkLocation;
        
        // Truncate latitude/longitude to the number of digits specified by locationPrecision.
        if (locationAwareness == LocationAwareness.LOCATION_AWARENESS_TRUNCATED) {
            double lat = result.getLatitude();
            double truncatedLat = BigDecimal.valueOf(lat)
                .setScale(locationPrecision, BigDecimal.ROUND_HALF_DOWN)
                .doubleValue();
            result.setLatitude(truncatedLat);
            
            double lon = result.getLongitude();
            double truncatedLon = BigDecimal.valueOf(lon)
                .setScale(locationPrecision, BigDecimal.ROUND_HALF_DOWN)
                .doubleValue();
            result.setLongitude(truncatedLon);
        }
        
        return result;
    }
    
    private String generateAdUrl() {
        StringBuilder sz = new StringBuilder("http://" + getServerHostname() + 
                MoPubView.AD_HANDLER);
        sz.append("?v=6&id=" + mAdUnitId);
        sz.append("&nv=" + MoPub.SDK_VERSION);
        
        String udid = Secure.getString(getContext().getContentResolver(), Secure.ANDROID_ID);
        String udidDigest = (udid == null) ? "" : Utils.sha1(udid);
        sz.append("&udid=sha:" + udidDigest);
        
        String keywords = addKeyword(mKeywords, getFacebookKeyword());
        if (keywords != null && keywords.length() > 0) {
            sz.append("&q=" + Uri.encode(keywords));
        }
        
        if (mLocation != null) {
            sz.append("&ll=" + mLocation.getLatitude() + "," + mLocation.getLongitude());
        }
        
        sz.append("&z=" + getTimeZoneOffsetString());
        
        int orientation = getResources().getConfiguration().orientation;
        String orString = DEVICE_ORIENTATION_UNKNOWN;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            orString = DEVICE_ORIENTATION_PORTRAIT;
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            orString = DEVICE_ORIENTATION_LANDSCAPE;
        } else if (orientation == Configuration.ORIENTATION_SQUARE) {
            orString = DEVICE_ORIENTATION_SQUARE;
        }
        sz.append("&o=" + orString);
        
        float density = getContext().getResources().getDisplayMetrics().density;
        sz.append("&sc_a=" + density);
        
        boolean mraid = true;
        try {
            Class.forName("com.mopub.mobileads.MraidView");
        } catch (ClassNotFoundException e) {
            mraid = false;
        }
        if (mraid) sz.append("&mr=1");
      
        return sz.toString();
    }
    
    private String getTimeZoneOffsetString() {
        SimpleDateFormat format = new SimpleDateFormat("Z");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }
    
    private String getFacebookKeyword() {
        try {
            Class<?> facebookKeywordProviderClass = Class.forName("com.mopub.mobileads.FacebookKeywordProvider");
            Method getKeywordMethod = facebookKeywordProviderClass.getMethod("getKeyword", Context.class);
            
            return (String) getKeywordMethod.invoke(facebookKeywordProviderClass, getContext());
        } catch (Exception exception) {
            return null;
        }
    }
    
    /*
     * Overrides the WebView's loadUrl() in order to expose HTTP response headers.
     */
    @Override
    public void loadUrl(String url) {
        if (url.startsWith("javascript:")) {
            super.loadUrl(url);
            return;
        }

        if (mIsLoading) {
            Log.i("MoPub", "Already loading an ad for " + mAdUnitId + ", wait to finish.");
            return;
        }
        
        mFailUrl = null;
        mUrl = url;
        mIsLoading = true;
        
        if (mAdFetcher != null) {
            mAdFetcher.fetchAdForUrl(mUrl);
        }
    }
    
    protected void configureAdViewUsingHeadersFromHttpResponse(HttpResponse response) {
        // Print the ad network type to the console.
        Header ntHeader = response.getFirstHeader("X-Networktype");
        if (ntHeader != null) Log.i("MoPub", "Fetching ad network type: " + ntHeader.getValue());

        // Set the redirect URL prefix: navigating to any matching URLs will send us to the browser.
        Header rdHeader = response.getFirstHeader("X-Launchpage");
        if (rdHeader != null) mRedirectUrl = rdHeader.getValue();
        else mRedirectUrl = null;

        // Set the URL that is prepended to links for click-tracking purposes.
        Header ctHeader = response.getFirstHeader("X-Clickthrough");
        if (ctHeader != null) mClickthroughUrl = ctHeader.getValue();
        else mClickthroughUrl = null;

        // Set the fall-back URL to be used if the current request fails.
        Header flHeader = response.getFirstHeader("X-Failurl");
        if (flHeader != null) mFailUrl = flHeader.getValue();
        else mFailUrl = null;
        
        // Set the URL to be used for impression tracking.
        Header imHeader = response.getFirstHeader("X-Imptracker");
        if (imHeader != null) mImpressionUrl = imHeader.getValue();
        else mImpressionUrl = null;
        
        // Set the webview's scrollability.
        Header scHeader = response.getFirstHeader("X-Scrollable");
        boolean enabled = false;
        if (scHeader != null) enabled = scHeader.getValue().equals("1");
        setWebViewScrollingEnabled(enabled);

        // Set the width and height.
        Header wHeader = response.getFirstHeader("X-Width");
        Header hHeader = response.getFirstHeader("X-Height");
        if (wHeader != null && hHeader != null) {
            mWidth = Integer.parseInt(wHeader.getValue().trim());
            mHeight = Integer.parseInt(hHeader.getValue().trim());
        }
        else {
            mWidth = 0;
            mHeight = 0;
        }

        // Set the auto-refresh time. A timer will be scheduled upon ad success or failure.
        Header rtHeader = response.getFirstHeader("X-Refreshtime");
        if (rtHeader != null) {
            mRefreshTimeMilliseconds = Integer.valueOf(rtHeader.getValue()) * 1000;
            if (mRefreshTimeMilliseconds < MINIMUM_REFRESH_TIME_MILLISECONDS) {
                mRefreshTimeMilliseconds = MINIMUM_REFRESH_TIME_MILLISECONDS;
            }
        }
        else mRefreshTimeMilliseconds = 0;
        
        // Set the allowed orientations for this ad.
        Header orHeader = response.getFirstHeader("X-Orientation");
        mAdOrientation = (orHeader != null) ? orHeader.getValue() : null;
    }
    
    private void setWebViewScrollingEnabled(boolean enabled) {
        if (enabled) {
            setOnTouchListener(null);
        } else {
            setOnTouchListener(new View.OnTouchListener() {
                public boolean onTouch(View v, MotionEvent event) {
                    return (event.getAction() == MotionEvent.ACTION_MOVE);
                }
            });
        }
    }
    
    private void adDidLoad() {
        Log.i("MoPub", "Ad successfully loaded.");
        mIsLoading = false;
        scheduleRefreshTimerIfEnabled();
        setAdContentView(this, getHtmlAdLayoutParams());
        mMoPubView.adLoaded();
    }
    
    public void setAdContentView(View view) {
        setAdContentView(view, WRAP_AND_CENTER_LAYOUT_PARAMS);
    }
    
    private void setAdContentView(View view, FrameLayout.LayoutParams layoutParams) {
        mMoPubView.removeAllViews();
        mMoPubView.addView(view, layoutParams);
    }
    
    private FrameLayout.LayoutParams getHtmlAdLayoutParams() {
        if (mWidth > 0 && mHeight > 0) {
          DisplayMetrics displayMetrics = getContext().getResources().getDisplayMetrics();
          
          int scaledWidth = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, mWidth,
                  displayMetrics);
          int scaledHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, mHeight,
                  displayMetrics);
          
          return new FrameLayout.LayoutParams(scaledWidth, scaledHeight, Gravity.CENTER);
        } else {
            return WRAP_AND_CENTER_LAYOUT_PARAMS;
        }
    }

    void adDidFail(MoPubErrorCode errorCode) {
        Log.i("MoPub", "Ad failed to load.");
        mIsLoading = false;
        scheduleRefreshTimerIfEnabled();
        mMoPubView.adFailed(errorCode);
    }

    private void adDidClose() {
        mMoPubView.adClosed();
    }
    
    private void handleCustomIntentFromUri(Uri uri) {
        registerClick();
        String action = uri.getQueryParameter("fnc");
        String adData = uri.getQueryParameter("data");
        Intent customIntent = new Intent(action);
        customIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (adData != null) customIntent.putExtra(EXTRA_AD_CLICK_DATA, adData);
        try {
            getContext().startActivity(customIntent);
        } catch (ActivityNotFoundException e) {
            Log.w("MoPub", "Could not handle custom intent: " + action +
                    ". Is your intent spelled correctly?");
        }
    }
    
    private void showBrowserForUrl(String url) {
        if (this.isDestroyed()) return;
        
        if (url == null || url.equals("")) url = "about:blank";
        Log.d("MoPub", "Final URI to show in browser: " + url);
        
        Context context = getContext();
        Intent intent = new Intent(context, MraidBrowser.class);
        intent.putExtra(MraidBrowser.URL_EXTRA, url);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            String action = intent.getAction();
            Log.w("MoPub", "Could not handle intent action: " + action
                    + ". Perhaps you forgot to declare com.mopub.mobileads.MraidBrowser"
                    + " in your Android manifest file.");
            
            getContext().startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
    
    @Deprecated
    public void customEventDidLoadAd() {
        mIsLoading = false;
        trackImpression();
        scheduleRefreshTimerIfEnabled();
    }

    @Deprecated
    public void customEventDidFailToLoadAd() {
        loadFailUrl(MoPubErrorCode.UNSPECIFIED);
    }

    @Deprecated
    public void customEventActionWillBegin() {
        registerClick();
    }
    
    protected boolean isDestroyed() {
        return mIsDestroyed;
    }
    
    /*
     * Clean up the internal state of the AdView.
     */
    protected void cleanup() {
        if (mIsDestroyed) {
            return;
        }

        setAutorefreshEnabled(false);
        cancelRefreshTimer();
        destroy();
        
        // WebView subclasses are not garbage-collected in a timely fashion on Froyo and below, 
        // thanks to some persistent references in WebViewCore. We manually release some resources
        // to compensate for this "leak".
        
        mAdFetcher.cleanup();
        mAdFetcher = null;
        
        mLocalExtras = null;
        
        mResponseString = null;
        
        mMoPubView.removeView(this);
        mMoPubView = null;
        
        // Flag as destroyed. LoadUrlTask checks this before proceeding in its onPostExecute().
        mIsDestroyed = true;
    }

    @Override
    public void reload() {
        Log.d("MoPub", "Reload ad: " + mUrl);
        loadUrl(mUrl);
    }

    public void loadFailUrl(MoPubErrorCode errorCode) {
        mIsLoading = false;
        
        Log.v("MoPub", "MoPubErrorCode: " + errorCode.toString());
        
        if (mFailUrl != null) {
            Log.d("MoPub", "Loading failover url: " + mFailUrl);
            loadUrl(mFailUrl);
        } else {
            // No other URLs to try, so signal a failure.
            adDidFail(MoPubErrorCode.NO_FILL);
        }
    }
    
    protected void loadResponseString(String responseString) {
        loadDataWithBaseURL("http://" + getServerHostname() + "/", responseString, "text/html",
                "utf-8", null);
    }

    protected void trackImpression() {
        new Thread(new Runnable() {
            public void run () {
                if (mImpressionUrl == null) return;
                
                DefaultHttpClient httpclient = new DefaultHttpClient();
                try {
                    HttpGet httpget = new HttpGet(mImpressionUrl);
                    httpget.addHeader("User-Agent", mUserAgent);
                    httpclient.execute(httpget);
                } catch (IllegalArgumentException e) {
                    Log.d("MoPub", "Impression tracking failed (IllegalArgumentException): "+mImpressionUrl);
                } catch (ClientProtocolException e) {
                    Log.d("MoPub", "Impression tracking failed (ClientProtocolException): "+mImpressionUrl);
                } catch (IOException e) {
                    Log.d("MoPub", "Impression tracking failed (IOException): "+mImpressionUrl);
                } finally {
                    httpclient.getConnectionManager().shutdown();
                }
            }
        }).start();
    }
    
    protected void registerClick() {
        new Thread(new Runnable() {
            public void run () {
                if (mClickthroughUrl == null) return;
                
                DefaultHttpClient httpclient = new DefaultHttpClient();
                HttpGet httpget = new HttpGet(mClickthroughUrl);
                httpget.addHeader("User-Agent", mUserAgent);
                try {
                    httpclient.execute(httpget);
                } catch (ClientProtocolException e) {
                    Log.i("MoPub", "Click tracking failed: "+mClickthroughUrl);
                } catch (IOException e) {
                    Log.i("MoPub", "Click tracking failed: "+mClickthroughUrl);
                } finally {
                    httpclient.getConnectionManager().shutdown();
                }
            }
        }).start();
    }

    protected void adAppeared() {
        this.loadUrl("javascript:webviewDidAppear();");
    }
    
    private Handler mRefreshHandler = new Handler();
    private Runnable mRefreshRunnable = new Runnable() {
        public void run() {
            loadAd();
        }
    };

    protected void scheduleRefreshTimerIfEnabled() {
        cancelRefreshTimer();
        if (!mAutorefreshEnabled || mRefreshTimeMilliseconds <= 0) return;
        mRefreshHandler.postDelayed(mRefreshRunnable, mRefreshTimeMilliseconds);
    }

    protected void cancelRefreshTimer() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
    }
    
    protected int getRefreshTimeMilliseconds() {
        return mRefreshTimeMilliseconds;
    }
    
    protected void setRefreshTimeMilliseconds(int refreshTimeMilliseconds) {
        mRefreshTimeMilliseconds = refreshTimeMilliseconds;
    }
    
    private String addKeyword(String keywords, String addition) {
        if (addition == null || addition.length() == 0) {
            return keywords;
        } else if (keywords == null || keywords.length() == 0) {
            return addition;
        } else {
            return keywords + "," + addition;
        }
    }
    
    protected String getServerHostname() {
        return mTesting ? MoPubView.HOST_FOR_TESTING : MoPubView.HOST;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    public String getKeywords() {
        return mKeywords;
    }

    public void setKeywords(String keywords) {
        mKeywords = keywords;
    }

    public Location getLocation() {
        return mLocation;
    }

    public void setLocation(Location location) {
        mLocation = location;
    }

    public String getAdUnitId() {
        return mAdUnitId;
    }

    public void setAdUnitId(String adUnitId) {
        mAdUnitId = adUnitId;
    }

    public void setTimeout(int milliseconds) {
        if (mAdFetcher != null) {
            mAdFetcher.setTimeout(milliseconds);
        }
    }

    public int getAdWidth() {
        return mWidth;
    }

    public int getAdHeight() {
        return mHeight;
    }
    
    public String getAdOrientation() {
        return mAdOrientation;
    }

    public String getClickthroughUrl() {
        return mClickthroughUrl;
    }
    
    public void setClickthroughUrl(String url) {
        mClickthroughUrl = url;
    }

    public String getRedirectUrl() {
        return mRedirectUrl;
    }

    public String getResponseString() {
        return mResponseString;
    }
    
    protected void setResponseString(String responseString) {
        mResponseString = responseString;
    }
    
    protected void setIsLoading(boolean isLoading) {
        mIsLoading = isLoading;
    }
    
    public void setAutorefreshEnabled(boolean enabled) {
        mAutorefreshEnabled = enabled;
        
        Log.d("MoPub", "Automatic refresh for " + mAdUnitId + " set to: " + enabled + ".");
        
        if (!mAutorefreshEnabled) cancelRefreshTimer();
        else scheduleRefreshTimerIfEnabled();
    }
    
    public boolean getAutorefreshEnabled() {
        return mAutorefreshEnabled;
    }
    
    public void setTesting(boolean testing) {
        mTesting = testing;
    }
    
    public boolean getTesting() {
        return mTesting;
    }
    
    public void forceRefresh() {
        mIsLoading = false;
        loadAd();
    }
    
    void setLocalExtras(Map<String, Object> localExtras) {
        mLocalExtras = (localExtras != null)
                ? new HashMap<String,Object>(localExtras)
                : new HashMap<String,Object>();
    }
    
    Map<String, Object> getLocalExtras() {
        return (mLocalExtras != null)
                ? new HashMap<String,Object>(mLocalExtras)
                : new HashMap<String,Object>();
    }
}
