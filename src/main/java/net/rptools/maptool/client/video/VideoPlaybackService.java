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

import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.rptools.lib.MD5Key;
import net.rptools.maptool.model.Asset;
import net.rptools.maptool.model.AssetManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Drives video-frame production for {@link Asset.Type#VIDEO} assets that are visible on the map.
 * Each registered video has a wall-clock start time; on every tick the service computes a looped
 * playhead, decodes the corresponding frame on its own thread, stashes it under the asset's MD5,
 * and pings registered {@link ImageObserver}s so the canvas repaints.
 *
 * <p>Decoding is serialized on a single daemon thread. With small clips that's tens of frames per
 * second across roughly the cache capacity (~8 simultaneous videos); for larger clips it degrades
 * gracefully — clips fall behind, no decoder is double-driven.
 */
public final class VideoPlaybackService implements AutoCloseable {

  private static final Logger log = LogManager.getLogger(VideoPlaybackService.class);
  private static final long TICK_PERIOD_MS = 33; // ~30 Hz target

  private static volatile VideoPlaybackService instance;

  private final VideoFrameSourceCache cache;
  private final ScheduledExecutorService ticker;
  private final ConcurrentMap<MD5Key, PlaybackState> states = new ConcurrentHashMap<>();

  /** Lazy singleton accessor. */
  public static VideoPlaybackService getInstance() {
    VideoPlaybackService s = instance;
    if (s == null) {
      synchronized (VideoPlaybackService.class) {
        s = instance;
        if (s == null) {
          s = new VideoPlaybackService();
          s.startTicker();
          instance = s;
        }
      }
    }
    return s;
  }

  VideoPlaybackService() {
    this(new VideoFrameSourceCache());
  }

  VideoPlaybackService(VideoFrameSourceCache cache) {
    this.cache = cache;
    this.ticker =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "video-playback-ticker");
              t.setDaemon(true);
              return t;
            });
  }

  private void startTicker() {
    ticker.scheduleAtFixedRate(
        () -> {
          try {
            tick(System.currentTimeMillis());
          } catch (Throwable t) {
            log.warn("Video tick failed", t);
          }
        },
        TICK_PERIOD_MS,
        TICK_PERIOD_MS,
        TimeUnit.MILLISECONDS);
  }

  /**
   * Returns the most recently decoded frame for {@code asset}, or {@code null} if no frame has been
   * produced yet. The first call also seeds an immediate decode so the next paint has something to
   * display, and registers {@code observer} (held weakly) for {@code imageUpdate} callbacks when
   * subsequent frames arrive.
   */
  public BufferedImage currentFrame(Asset asset, ImageObserver observer) {
    if (asset.getType() != Asset.Type.VIDEO) {
      throw new IllegalArgumentException("Asset is not a video: " + asset.getType());
    }
    MD5Key key = asset.getMD5Key();
    PlaybackState state =
        states.computeIfAbsent(key, k -> new PlaybackState(System.currentTimeMillis()));
    if (observer != null) {
      state.addObserver(observer);
    }
    BufferedImage frame = state.latestFrame.get();
    if (frame == null) {
      // Seed the first frame so subsequent paints have something to display.
      ticker.execute(() -> decodeOne(asset, key, state, System.currentTimeMillis()));
    }
    return frame;
  }

  /** Drops cached state for a video; the next {@link #currentFrame} restarts playback at zero. */
  public void evict(MD5Key key) {
    states.remove(key);
    cache.evict(key);
  }

  /** Visible for testing — drives one tick at the given wall clock. */
  void tick(long nowMillis) {
    for (Map.Entry<MD5Key, PlaybackState> entry : states.entrySet()) {
      MD5Key key = entry.getKey();
      Asset asset = AssetManager.getAsset(key);
      if (asset == null || asset.getType() != Asset.Type.VIDEO) {
        continue;
      }
      decodeOne(asset, key, entry.getValue(), nowMillis);
    }
  }

  private void decodeOne(Asset asset, MD5Key key, PlaybackState state, long nowMillis) {
    try {
      VideoFrameSource source = cache.get(asset);
      long playheadMicros =
          computePlayheadMicros(nowMillis, state.startMillis, source.durationMicros());
      BufferedImage frame = source.frameAt(playheadMicros);
      if (frame != null) {
        state.latestFrame.set(frame);
        state.notifyObservers(frame);
      }
    } catch (IOException e) {
      log.warn("Failed to open video source for {}", key, e);
    } catch (RuntimeException e) {
      log.warn("Decode error for {}", key, e);
    }
  }

  /** Computes a looped playhead position; behaves sensibly for unknown duration. */
  static long computePlayheadMicros(long nowMillis, long startMillis, long durationMicros) {
    long elapsedMicros = (nowMillis - startMillis) * 1000L;
    if (durationMicros <= 0) {
      return Math.max(0L, elapsedMicros);
    }
    long mod = elapsedMicros % durationMicros;
    return mod < 0 ? mod + durationMicros : mod;
  }

  /** Visible for testing — number of currently registered videos. */
  int activeVideos() {
    return states.size();
  }

  @Override
  public void close() {
    ticker.shutdownNow();
    cache.close();
    states.clear();
  }

  private static final class PlaybackState {
    final long startMillis;
    final AtomicReference<BufferedImage> latestFrame = new AtomicReference<>();
    // Weak so a discarded ZoneRenderer doesn't keep the playback service from releasing
    // its reference. Synchronized because WeakHashMap is not thread-safe and we touch it
    // from both the ticker thread (notifyObservers) and the EDT (addObserver via paint).
    final Set<ImageObserver> observers =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    PlaybackState(long startMillis) {
      this.startMillis = startMillis;
    }

    void addObserver(ImageObserver o) {
      observers.add(o);
    }

    void notifyObservers(BufferedImage frame) {
      ImageObserver[] snapshot;
      synchronized (observers) {
        snapshot = observers.toArray(new ImageObserver[0]);
      }
      int w = frame.getWidth();
      int h = frame.getHeight();
      for (ImageObserver o : snapshot) {
        o.imageUpdate(frame, ImageObserver.ALLBITS, 0, 0, w, h);
      }
    }
  }
}
