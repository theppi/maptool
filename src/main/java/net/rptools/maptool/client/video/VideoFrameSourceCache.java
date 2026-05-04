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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.rptools.lib.MD5Key;
import net.rptools.maptool.model.Asset;

/**
 * Bounded LRU cache of open {@link VideoFrameSource} instances, keyed by asset MD5. Sources evicted
 * by capacity are {@link VideoFrameSource#close() closed} so their decoders and temp files are
 * released.
 *
 * <p>The cache is intentionally keyed by asset only — multiple tokens displaying the same video
 * share one decoder. If two tokens need to scrub independently we can extend the key later.
 */
public final class VideoFrameSourceCache implements AutoCloseable {

  private static final int DEFAULT_CAPACITY = 8;

  private final int capacity;
  private final LinkedHashMap<MD5Key, VideoFrameSource> entries;

  public VideoFrameSourceCache() {
    this(DEFAULT_CAPACITY);
  }

  public VideoFrameSourceCache(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    this.capacity = capacity;
    this.entries =
        new LinkedHashMap<>(capacity, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<MD5Key, VideoFrameSource> eldest) {
            if (size() > VideoFrameSourceCache.this.capacity) {
              eldest.getValue().close();
              return true;
            }
            return false;
          }
        };
  }

  /**
   * Returns a cached source for the asset, opening one if necessary.
   *
   * @throws IllegalArgumentException if the asset is not a video
   * @throws IOException if a fresh source can't be opened
   */
  public synchronized VideoFrameSource get(Asset asset) throws IOException {
    if (asset.getType() != Asset.Type.VIDEO) {
      throw new IllegalArgumentException("Asset is not a video: " + asset.getType());
    }
    MD5Key key = asset.getMD5Key();
    VideoFrameSource existing = entries.get(key);
    if (existing != null) {
      return existing;
    }
    VideoFrameSource fresh = VideoFrameSource.open(asset);
    entries.put(key, fresh);
    return fresh;
  }

  /** Removes and closes the source for the given key, if any. */
  public synchronized void evict(MD5Key key) {
    VideoFrameSource removed = entries.remove(key);
    if (removed != null) {
      removed.close();
    }
  }

  /** Current number of cached sources. */
  public synchronized int size() {
    return entries.size();
  }

  @Override
  public synchronized void close() {
    entries.values().forEach(VideoFrameSource::close);
    entries.clear();
  }
}
