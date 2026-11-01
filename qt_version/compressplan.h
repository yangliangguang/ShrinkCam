// AI生成
#ifndef COMPRESSPLAN_H
#define COMPRESSPLAN_H

#include <QString>

// 压缩质量档位
enum class Quality {
    Extreme,   // 720p · 体积最小
    Balanced,  // 1080p · 折中
    Clear      // 1080p · 画质优先
};

// 质量档位信息
struct QualityInfo {
    QString label;
    QString desc;
};

inline QualityInfo qualityInfo(Quality q) {
    switch (q) {
    case Quality::Extreme:  return {"极限压缩", "720p · 体积最小 · 发微信首选"};
    case Quality::Balanced: return {"均衡", "1080p · 体积与画质折中"};
    case Quality::Clear:    return {"清晰", "1080p · 画质优先"};
    }
    return {"", ""};
}

// 分辨率处理策略
enum class SizeMode {
    Scale,    // 等比缩小
    Original  // 保持原尺寸
};

// 视频元信息
struct VideoInfo {
    int width = 0;
    int height = 0;
    int rotation = 0;
    qint64 durationUs = 0;
    qint64 sizeBytes = 0;
    int bitrate = 0;
    float frameRate = 30.0f;
    QString codec;
    bool hasAudio = false;
    int audioSampleRate = 44100;
    int audioChannels = 2;

    int displayW() const { return (rotation == 90 || rotation == 270) ? height : width; }
    int displayH() const { return (rotation == 90 || rotation == 270) ? width : height; }
};

// 压缩计划
struct CompressPlan {
    int outWidth = 0;
    int outHeight = 0;
    int videoBitrate = 0;
    int audioBitrate = 0;
    int outFps = 30;
    bool useHevc = true;
    int rotation = 0;
    qint64 estimateBytes = 0;

    QString codecName() const { return useHevc ? "H.265 / HEVC" : "H.264 / AVC"; }

    static CompressPlan make(const VideoInfo &info, Quality quality,
                             qint64 targetBytes, float factor = 1.0f,
                             SizeMode sizeMode = SizeMode::Scale,
                             bool keepAudio = true)
    {
        int shortCap;
        float bpp;
        int minKbps;
        switch (quality) {
        case Quality::Extreme:  shortCap = 720;  bpp = 0.055f; minKbps = 180;  break;
        case Quality::Balanced: shortCap = 1080; bpp = 0.10f;  minKbps = 700;  break;
        case Quality::Clear:    shortCap = 1080; bpp = 0.17f;  minKbps = 1600; break;
        }

        // 源编码坐标系宽高：像素不旋转，直接用 info.width/height
        int srcEncW = qMax(2, info.width);
        int srcEncH = qMax(2, info.height);
        int srcShort = qMin(srcEncW, srcEncH);
        int srcLong  = qMax(srcEncW, srcEncH);

        int wEven, hEven;
        if (sizeMode == SizeMode::Original) {
            wEven = even(srcEncW);
            hEven = even(srcEncH);
        } else {
            int short_ = qMin(srcShort, shortCap);
            float ratio = (float)short_ / srcShort;
            int longOut = qMax(2, even((int)(srcLong * ratio)));
            int s = even(short_);
            bool sourceLandscape = srcEncW >= srcEncH;
            wEven = sourceLandscape ? longOut : s;
            hEven = sourceLandscape ? s : longOut;
        }

        int fps;
        if (info.frameRate <= 0) fps = 30;
        else if (quality == Quality::Extreme) fps = 24;
        else if (info.frameRate > 40) fps = 30;
        else fps = qBound(15, (int)info.frameRate, 30);

        qint64 pixelsPerSec = (qint64)wEven * hEven * fps;
        int vKbps = (int)(pixelsPerSec * bpp / 1000.0f * factor);
        vKbps = qMax(vKbps, minKbps);

        double durationSec = qMax(0.1, (double)info.durationUs / 1000000.0);
        int audioKbps = (keepAudio && info.hasAudio) ? 128 : 0;
        if (targetBytes > 0) {
            double totalKbps = (targetBytes * 8.0 / durationSec / 1000.0) * 0.94;
            int v = (int)totalKbps - audioKbps;
            vKbps = qMin(vKbps, qMax(30, v));
        }
        if (info.bitrate > 0) {
            vKbps = qMin(vKbps, qMax(100, (int)(info.bitrate * 0.85f / 1000.0f)));
        }
        vKbps = qBound(60, vKbps, 40000);
        int aKbps = audioKbps;

        bool hevc = true; // FFmpeg 软编 libx265，桌面端总是可用
        qint64 estimate = qMax<qint64>(1, (qint64)(vKbps + aKbps) * 1000 / 8 * (qint64)durationSec);

        // 方向原封不动：像素不旋转，沿用源的旋转元数据
        return CompressPlan{
            wEven, hEven,
            vKbps * 1000,
            aKbps * 1000,
            fps,
            hevc,
            info.rotation,
            estimate
        };
    }

private:
    static int even(int v) { return qMax(2, v - (v % 2)); }
};

// 格式化工具
namespace Fmt {
    inline QString size(qint64 bytes) {
        if (bytes <= 0) return "0 B";
        double kb = bytes / 1024.0, mb = kb / 1024.0, gb = mb / 1024.0;
        if (gb >= 1) return QString::asprintf("%.2f GB", gb);
        if (mb >= 1) return QString::asprintf("%.2f MB", mb);
        if (kb >= 1) return QString::asprintf("%.0f KB", kb);
        return QString("%1 B").arg(bytes);
    }
    inline QString duration(qint64 us) {
        if (us <= 0) return "00:00";
        qint64 s = us / 1000000, m = s / 60, sec = s % 60;
        if (m >= 60) return QString::asprintf("%d:%02d:%02d", m/60, m%60, sec);
        return QString::asprintf("%02d:%02d", m, sec);
    }
    inline QString kbps(int bps) {
        if (bps >= 1000000) return QString::asprintf("%.1f Mbps", bps / 1000000.0f);
        return QString("%1 kbps").arg(bps / 1000);
    }
    inline QString ratio(qint64 src, qint64 dst) {
        if (dst <= 0 || src <= 0) return "-";
        return QString::asprintf("%.1fx", (double)src / (double)dst);
    }
    inline QString percent(double saved) {
        return QString::asprintf("%.0f%%", saved * 100);
    }
}

#endif // COMPRESSPLAN_H
