# ExoPlayer #

ExoPlayer is an application level media player for Android. It provides an
alternative to Android’s MediaPlayer API for playing audio and video both
locally and over the Internet.

## Documentation ##
This repository is adding the functionality of downloading a widevine licence using OfflineLicenceHelper in the exoplayer's demo application.
Above action will give us a Base64 offlineAssetKeyId id which is then saved in shared preference to use for playing content offline.

You can see the above change in DownloadTracker class
```java
private void downloadLicence(DrmInitData data) {
      try {
        offlineLicenseHelper = OfflineLicenseHelper.newWidevineInstance("licence-url-supporting-offline-playback"
            httpDataSourceFactory);
        Log.d(TAG, "drmInitData " + data);
        byte[] keySet = offlineLicenseHelper.downloadLicense(data);
        SharedPreferences prefs = context.getSharedPreferences("download", Context.MODE_PRIVATE);
        prefs.edit().putString("offline-id", Base64.encodeToString(keySet, Base64.DEFAULT)).apply();
        Log.i(TAG, keySet.toString());
      } catch (Exception e) {
        Log.d(TAG, e.getMessage());
      }
    }
```

And then at the time of playback this key-pair is retrieved from the shared preference and used for the offline playback
You can see the above change regarding this in PlayerActivity class
```java
      String offlineAssetKeyIdStr = this.getSharedPreferences("download", Context.MODE_PRIVATE)
          .getString("offline-id", "");
      byte[] offlineAssetKeyId = Base64.decode(offlineAssetKeyIdStr, Base64.DEFAULT);
      String drmLicenseUrl = "licence-url-supporting-offline-playback";
      boolean multiSession = false;
      try {
        UUID drmSchemeUuid = Util.getDrmUuid("widevine");
        drmSessionManager = buildDrmSessionManagerV18(
                drmSchemeUuid, drmLicenseUrl, null, multiSession);
      } catch (UnsupportedDrmException e) { }
      //this is the additional part which needs to be done specifically for offline drm playback
      drmSessionManager.setMode(DefaultDrmSessionManager.MODE_PLAYBACK, offlineAssetKeyId);

```


