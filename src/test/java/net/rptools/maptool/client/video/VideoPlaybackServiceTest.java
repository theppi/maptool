/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.client.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class VideoPlaybackServiceTest {

  private static final int CLIP_WIDTH = 64;
  private static final int CLIP_HEIGHT = 48;
  private static final int CLIP_FRAMES = 10;
  private static final int CLIP_FPS = 10;

  private static byte[] clipBytes;

  @BeforeAll
  static void synthesizeClip() throws Exception {
    Path tmp = Files.createTempFile("maptool-test-clip-", ".mp4");
    try (FFmpegFrameRecorder rec = new FFmpegFrameRecorder(tmp.toFile(), CLIP_WIDTH, CLIP_HEIGHT)) {
      rec.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
      rec.setFormat("mp4");
      rec.setFrameRate(CLIP_FPS);
      rec.start();
      try (Java2DFrameConverter conv = new Java2DFrameConverter()) {
        BufferedImage img = new BufferedImage(CLIP_WIDTH, CLIP_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
          for (int i = 0; i < CLIP_FRAMES; i++) {
            g.setColor(new Color(i * 25 % 256, 0, 0));
            g.fillRect(0, 0, CLIP_WIDTH, CLIP_HEIGHT);
            rec.record(conv.convert(img));
          }
        } finally {
          g.dispose();
        }
      }
      rec.stop();
    }
    clipBytes = Files.readAllBytes(tmp);
    Files.deleteIfExists(tmp);
  }

  private Asset videoAsset() {
    return Asset.createVideoAsset("clip.mp4", clipBytes);
  }

  @Test
  void rejectsNonVideoAsset() {
    Asset image = Asset.createImageAsset("img.png", new byte[] {1, 2, 3});
    try (VideoPlaybackService service = new VideoPlaybackService()) {
      assertThrows(IllegalArgumentException.class, () -> service.currentFrame(image, null));
    }
  }

  @Test
  void firstCallReturnsNullThenTickProducesFrame() {
    Asset asset = videoAsset();
    try (VideoPlaybackService service = new VideoPlaybackService();
        MockedStatic<AssetManager> assets = mockStatic(AssetManager.class)) {
      assets.when(() -> AssetManager.getAsset(asset.getMD5Key())).thenReturn(asset);

      assertNull(service.currentFrame(asset, null), "no frame before first tick");
      assertEquals(1, service.activeVideos());

      service.tick(System.currentTimeMillis());

      BufferedImage frame = service.currentFrame(asset, null);
      assertNotNull(frame);
      assertEquals(CLIP_WIDTH, frame.getWidth());
      assertEquals(CLIP_HEIGHT, frame.getHeight());
    }
  }

  @Test
  void observerIsNotifiedOnTick() {
    Asset asset = videoAsset();
    ImageObserver observer = mock(ImageObserver.class);
    try (VideoPlaybackService service = new VideoPlaybackService();
        MockedStatic<AssetManager> assets = mockStatic(AssetManager.class)) {
      assets.when(() -> AssetManager.getAsset(asset.getMD5Key())).thenReturn(asset);

      service.currentFrame(asset, observer);
      service.tick(System.currentTimeMillis());

      verify(observer, atLeastOnce())
          .imageUpdate(
              any(),
              org.mockito.ArgumentMatchers.eq(ImageObserver.ALLBITS),
              anyInt(),
              anyInt(),
              org.mockito.ArgumentMatchers.eq(CLIP_WIDTH),
              org.mockito.ArgumentMatchers.eq(CLIP_HEIGHT));
    }
  }

  @Test
  void evictRemovesState() {
    Asset asset = videoAsset();
    try (VideoPlaybackService service = new VideoPlaybackService();
        MockedStatic<AssetManager> assets = mockStatic(AssetManager.class)) {
      assets.when(() -> AssetManager.getAsset(asset.getMD5Key())).thenReturn(asset);
      service.currentFrame(asset, null);
      assertEquals(1, service.activeVideos());
      service.evict(asset.getMD5Key());
      assertEquals(0, service.activeVideos());
    }
  }

  @Test
  void computePlayheadLoopsAcrossDuration() {
    long duration = 1_000_000L; // 1 second
    long start = 1_000L;

    assertEquals(0L, VideoPlaybackService.computePlayheadMicros(start, start, duration));
    assertEquals(
        500_000L, VideoPlaybackService.computePlayheadMicros(start + 500, start, duration));
    // 1500ms after start = 1.5s elapsed → loops to 0.5s mark
    assertEquals(
        500_000L, VideoPlaybackService.computePlayheadMicros(start + 1500, start, duration));
  }

  @Test
  void computePlayheadHandlesUnknownDuration() {
    long start = 0L;
    long playhead = VideoPlaybackService.computePlayheadMicros(start + 250, start, 0L);
    assertTrue(playhead >= 0);
    assertEquals(250_000L, playhead);
  }
}
