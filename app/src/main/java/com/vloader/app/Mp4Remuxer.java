package com.vloader.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用系统 MediaExtractor + MediaMuxer 把拼接后的 TS 重封装为标准 MP4 容器。
 * 逐轨复制编码数据，不转码、不损失画质，不依赖第三方库。
 */
public final class Mp4Remuxer {

    public static boolean remux(File ts, File mp4) {
        if (!ts.exists() || ts.length() == 0) {
            return false;
        }
        if (mp4.exists()) {
            mp4.delete();
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(ts.getAbsolutePath());
            muxer = new MediaMuxer(mp4.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            Map<Integer, Integer> trackMap = new LinkedHashMap<>();
            Map<Integer, Long> basePts = new HashMap<>();
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    trackMap.put(i, muxer.addTrack(f));
                    basePts.put(i, -1L);
                    extractor.selectTrack(i);
                }
            }
            if (trackMap.isEmpty()) {
                throw new IllegalStateException("未找到可封装的音视频轨道");
            }
            muxer.start();

            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int trackIndex = extractor.getSampleTrackIndex();
                if (trackIndex < 0) {
                    break;
                }
                Integer outTrack = trackMap.get(trackIndex);
                buf.clear();
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) {
                    break;
                }
                if (outTrack != null) {
                    long pts = extractor.getSampleTime();
                    long base = basePts.get(trackIndex);
                    if (base < 0) {
                        base = pts > 0 ? pts : 0;
                        basePts.put(trackIndex, base);
                    }
                    // 将起始 PTS 归一化到 0 起点，保证时间戳连续
                    info.set(0, size, Math.max(0, pts - base),
                            extractor.getSampleFlags() & MediaCodec.BUFFER_FLAG_SYNC_FRAME);
                    muxer.writeSampleData(outTrack, buf, info);
                }
                extractor.advance();
            }
            muxer.stop();
            return mp4.exists() && mp4.length() > 0;
        } catch (Exception e) {
            if (mp4.exists()) {
                mp4.delete();
            }
            return false;
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Mp4Remuxer() {
    }
}
