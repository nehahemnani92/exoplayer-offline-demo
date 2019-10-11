/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.google.android.exoplayer2.playbacktests.gts;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import android.media.MediaDrm.MediaDrmStateException;
import android.net.Uri;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmSession.DrmSessionException;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.OfflineLicenseHelper;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.dash.manifest.Period;
import com.google.android.exoplayer2.source.dash.manifest.Representation;
import com.google.android.exoplayer2.source.hls.offline.HlsDownloader;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser;
import com.google.android.exoplayer2.testutil.ActionSchedule;
import com.google.android.exoplayer2.testutil.HostActivity;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.io.IOException;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests Widevine encrypted DASH playbacks using offline keys. */
@RunWith(AndroidJUnit4.class)
public final class DashWidevineOfflineTest {

  private static final String TAG = "DashWidevineOfflineTest";
  private static final String USER_AGENT = "ExoPlayerPlaybackTests";

  private DashTestRunner testRunner;
  private DefaultHttpDataSourceFactory httpDataSourceFactory;
  private OfflineLicenseHelper<FrameworkMediaCrypto> offlineLicenseHelper;
  private byte[] offlineLicenseKeySetId;

  @Rule public ActivityTestRule<HostActivity> testRule = new ActivityTestRule<>(HostActivity.class);

  @Before
  public void setUp() throws Exception {
    testRunner =
        new DashTestRunner(TAG, testRule.getActivity())
            .setStreamName("test_widevine_h264_fixed_offline")
            .setManifestUrl(DashTestData.WIDEVINE_H264_MANIFEST)
            .setWidevineInfo(MimeTypes.VIDEO_H264, true)
            .setFullPlaybackNoSeeking(true)
            .setCanIncludeAdditionalVideoFormats(false)
            .setAudioVideoFormats(
                DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
                DashTestData.WIDEVINE_H264_CDD_FIXED);

    boolean useL1Widevine = DashTestRunner.isL1WidevineAvailable(MimeTypes.VIDEO_H264);
    String widevineLicenseUrl = DashTestData.getWidevineLicenseUrl(true, useL1Widevine);
    httpDataSourceFactory = new DefaultHttpDataSourceFactory(USER_AGENT);
    if (Util.SDK_INT >= 18) {
      offlineLicenseHelper = OfflineLicenseHelper.newWidevineInstance(widevineLicenseUrl,
          httpDataSourceFactory);
    }
  }

  @After
  public void tearDown() throws Exception {
    testRunner = null;
    if (offlineLicenseKeySetId != null) {
      releaseLicense();
    }
    if (offlineLicenseHelper != null) {
      offlineLicenseHelper.release();
    }
    offlineLicenseHelper = null;
    httpDataSourceFactory = null;
  }

  // Offline license tests

  @Test
  public void testWidevineOfflineLicenseV22() throws Exception {
    if (Util.SDK_INT < 22) {
      return; // Pass.
    }
    downloadLicense();
    testRunner.run();

    // Renew license after playback should still work
    offlineLicenseKeySetId = offlineLicenseHelper.renewLicense(offlineLicenseKeySetId);
    assertThat(offlineLicenseKeySetId).isNotNull();
  }

  @Test
  public void testWidevineOfflineReleasedLicenseV22() throws Throwable {
    if (Util.SDK_INT < 22) {
      return; // Pass.
    }
    downloadLicense();
    releaseLicense(); // keySetId no longer valid.

    try {
      testRunner.run();
      fail("Playback should fail because the license has been released.");
    } catch (Throwable e) {
      // Get the root cause
      while (true) {
        Throwable cause = e.getCause();
        if (cause == null || cause == e) {
          break;
        }
        e = cause;
      }
      // It should be a MediaDrmStateException instance
      if (!(e instanceof MediaDrmStateException)) {
        throw e;
      }
    }
  }

  @Test
  public void testWidevineOfflineExpiredLicenseV22() throws Exception {
    if (Util.SDK_INT < 22) {
      return; // Pass.
    }
    downloadLicense();

    // Wait until the license expires
    long licenseDuration =
        offlineLicenseHelper.getLicenseDurationRemainingSec(offlineLicenseKeySetId).first;
    assertWithMessage(
            "License duration should be less than 30 sec. " + "Server settings might have changed.")
        .that(licenseDuration < 30)
        .isTrue();
    while (licenseDuration > 0) {
      synchronized (this) {
        wait(licenseDuration * 1000 + 2000);
      }
      long previousDuration = licenseDuration;
      licenseDuration =
          offlineLicenseHelper.getLicenseDurationRemainingSec(offlineLicenseKeySetId).first;
      assertWithMessage("License duration should be decreasing.")
          .that(previousDuration > licenseDuration)
          .isTrue();
    }

    // DefaultDrmSessionManager should renew the license and stream play fine
    testRunner.run();
  }

  @Test
  public void testWidevineOfflineLicenseExpiresOnPauseV22() throws Exception {
    if (Util.SDK_INT < 22) {
      return; // Pass.
    }
    downloadLicense();

    // During playback pause until the license expires then continue playback
    Pair<Long, Long> licenseDurationRemainingSec =
        offlineLicenseHelper.getLicenseDurationRemainingSec(offlineLicenseKeySetId);
    long licenseDuration = licenseDurationRemainingSec.first;
    assertWithMessage(
            "License duration should be less than 30 sec. " + "Server settings might have changed.")
        .that(licenseDuration < 30)
        .isTrue();
    ActionSchedule schedule = new ActionSchedule.Builder(TAG)
        .waitForPlaybackState(Player.STATE_READY)
        .delay(3000).pause().delay(licenseDuration * 1000 + 2000).play().build();

    // DefaultDrmSessionManager should renew the license and stream play fine
    testRunner.setActionSchedule(schedule).run();
  }

  private void downloadLicense() throws InterruptedException, DrmSessionException, IOException {
    DataSource dataSource = httpDataSourceFactory.createDataSource();
    DashManifest dashManifest = DashUtil.loadManifest(dataSource,
        Uri.parse(DashTestData.WIDEVINE_H264_MANIFEST));
    DrmInitData drmInitData = DashUtil.loadDrmInitData(dataSource, dashManifest.getPeriod(0));
    offlineLicenseKeySetId = offlineLicenseHelper.downloadLicense(drmInitData);
    assertThat(offlineLicenseKeySetId).isNotNull();
    assertThat(offlineLicenseKeySetId.length).isGreaterThan(0);
    testRunner.setOfflineLicenseKeySetId(offlineLicenseKeySetId);
  }

  private void downloadHlsLicense() throws InterruptedException, DrmSessionException, IOException {
    HlsPlaylistParser parser = new HlsPlaylistParser();


    DataSource dataSource = httpDataSourceFactory.createDataSource();
//    HlsMediaPlaylist playlist = ParsingLoadable.load(
//        dataSource, new HlsPlaylistParser(), getCompressibleDataSpec(Uri.parse(DashTestData.WIDEVINE_H264_MANIFEST)), C.DATA_TYPE_MANIFEST);
//    DrmInitData drmInitData = DashUtil.loadDrmInitData(dataSource, playlist.getPeriod(0));
//    offlineLicenseKeySetId = offlineLicenseHelper.downloadLicense(drmInitData);
    assertThat(offlineLicens  eKeySetId).isNotNull();
    assertThat(offlineLicenseKeySetId.length).isGreaterThan(0);
    testRunner.setOfflineLicenseKeySetId(offlineLicenseKeySetId);
  }


  public static @Nullable
  DrmInitData loadDrmInitData(DataSource dataSource, Period period)
      throws IOException, InterruptedException {
    int primaryTrackType = C.TRACK_TYPE_VIDEO;
    Representation representation = getFirstRepresentation(period, primaryTrackType);
    if (representation == null) {
      primaryTrackType = C.TRACK_TYPE_AUDIO;
      representation = getFirstRepresentation(period, primaryTrackType);
      if (representation == null) {
        return null;
      }
    }
    Format manifestFormat = representation.format;
    Format sampleFormat = DashUtil.loadSampleFormat(dataSource, primaryTrackType, representation);
    return sampleFormat == null
        ? manifestFormat.drmInitData
        : sampleFormat.copyWithManifestFormatInfo(manifestFormat).drmInitData;
  }

  private static @Nullable Representation getFirstRepresentation(Period period, int type) {
    int index = period.getAdaptationSetIndex(type);
    if (index == C.INDEX_UNSET) {
      return null;
    }
    List<Representation> representations = period.adaptationSets.get(index).representations;
    return representations.isEmpty() ? null : representations.get(0);
  }
  private void releaseLicense() throws DrmSessionException {
    offlineLicenseHelper.releaseLicense(offlineLicenseKeySetId);
    offlineLicenseKeySetId = null;
  }

  protected static DataSpec getCompressibleDataSpec(Uri uri) {
    return new DataSpec(
        uri,
        /* absoluteStreamPosition= */ 0,
        /* length= */ C.LENGTH_UNSET,
        /* key= */ null,
        /* flags= */ DataSpec.FLAG_ALLOW_GZIP);
  }

}
