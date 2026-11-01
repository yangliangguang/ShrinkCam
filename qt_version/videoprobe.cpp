// AI生成
#include "videoprobe.h"
#include <QFile>
#include <QFileInfo>

static quint32 readBE32(const QByteArray &d, int off) {
    if (off + 4 > d.size()) return 0;
    return (quint32((quint8)d[off]) << 24) | (quint32((quint8)d[off+1]) << 16) |
           (quint32((quint8)d[off+2]) << 8)  | quint32((quint8)d[off+3]);
}
static quint64 readBE64(const QByteArray &d, int off) {
    return (quint64(readBE32(d, off)) << 32) | readBE32(d, off + 4);
}
static quint32 readLE32(const QByteArray &d, int off) {
    if (off + 4 > d.size()) return 0;
    return quint32((quint8)d[off]) | (quint32((quint8)d[off+1]) << 8) |
           (quint32((quint8)d[off+2]) << 16) | (quint32((quint8)d[off+3]) << 24);
}
static QString tagStr(const QByteArray &d, int off) {
    if (off + 4 > d.size()) return QString();
    return QString::fromLatin1(d.constData() + off, 4);
}

// 从 tkhd 盒子提取旋转和宽高
static bool parseTkhd(const QByteArray &d, int boxOff, int boxEnd, int &rot, int &w, int &h) {
    int body = boxOff + 8;
    if (body + 4 > boxEnd) return false;
    int version = (quint8)d[body];
    int matOff, whOff;
    if (version == 1) {
        matOff = body + 52;
        whOff  = body + 52 + 36;
    } else {
        matOff = body + 40;
        whOff  = body + 40 + 36;
    }
    if (matOff + 36 <= boxEnd) {
        int a = (int)readBE32(d, matOff);
        int c = (int)readBE32(d, matOff + 12);
        float aF = a / 65536.0f, cF = c / 65536.0f;
        if (qAbs(aF - 1.0f) < 0.01f && qAbs(cF) < 0.01f) rot = 0;
        else if (qAbs(aF) < 0.01f && qAbs(cF - 1.0f) < 0.01f) rot = 90;
        else if (qAbs(aF + 1.0f) < 0.01f && qAbs(cF) < 0.01f) rot = 180;
        else if (qAbs(aF) < 0.01f && qAbs(cF + 1.0f) < 0.01f) rot = 270;
        else rot = 0;
    }
    if (whOff + 8 <= boxEnd) {
        w = (int)(readBE32(d, whOff) >> 16);
        h = (int)(readBE32(d, whOff + 4) >> 16);
        return true;
    }
    return false;
}

static void scanBoxes(const QByteArray &d, int from, int to,
                      int &rot, int &w, int &h, qint64 &dur, int &ts,
                      QString &codec, bool &gotTkhd) {
    int off = from, end = qMin(to, d.size());
    while (off + 8 <= end) {
        quint32 size = readBE32(d, off);
        if (size < 8) break;
        QString type = tagStr(d, off + 4);
        int body = off + 8;
        int boxEnd = qMin(off + (int)size, end);
        if (type == "tkhd" && !gotTkhd) {
            gotTkhd = parseTkhd(d, off, boxEnd, rot, w, h);
        } else if (type == "mdhd") {
            int version = (quint8)d[body];
            if (version == 1) { ts = (int)readBE32(d, body+20); dur = (qint64)readBE64(d, body+24); }
            else { ts = (int)readBE32(d, body+12); dur = (qint64)readBE32(d, body+16); }
        } else if (type == "stsd" && body + 12 <= end) {
            codec = tagStr(d, body + 8 + 4);
        } else if (type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl") {
            scanBoxes(d, body, boxEnd, rot, w, h, dur, ts, codec, gotTkhd);
        }
        off += size;
    }
}

// 在 data 中搜索 "moov" 标记，返回 moov 盒子起始偏移（size字段位置），-1 表示未找到
static int findMoov(const QByteArray &data) {
    for (int i = 4; i + 4 <= data.size(); i++) {
        if (data[i] == 'm' && data[i+1] == 'o' && data[i+2] == 'o' && data[i+3] == 'v') {
            if (i >= 4) {
                quint32 sz = readBE32(data, i - 4);
                if (sz >= 8 && sz <= (quint32)data.size() + 1024 * 1024)
                    return i - 4;
            }
        }
    }
    return -1;
}

// 解析 AVI 文件头 (RIFF/AVI 格式，小端)
static bool probeAVI(const QByteArray &data, int &w, int &h, qint64 &durUs) {
    if (data.size() < 32 || tagStr(data, 0) != "RIFF" || tagStr(data, 8) != "AVI ")
        return false;
    // 搜索 "avih" 标记
    for (int i = 12; i + 56 <= data.size(); i++) {
        if (data[i] == 'a' && data[i+1] == 'v' && data[i+2] == 'i' && data[i+3] == 'h') {
            int base = i + 8; // 跳过 size 字段
            if (base + 56 <= data.size()) {
                quint32 usPerFrame = readLE32(data, base);      // dwMicroSecPerFrame
                quint32 totalFrames = readLE32(data, base + 16); // dwTotalFrames
                w = (int)readLE32(data, base + 32);              // dwWidth
                h = (int)readLE32(data, base + 36);              // dwHeight
                if (usPerFrame > 0 && totalFrames > 0)
                    durUs = (qint64)usPerFrame * totalFrames;
                return true;
            }
            break;
        }
    }
    return false;
}

VideoInfo VideoProbe::probe(const QString &filePath)
{
    VideoInfo info;
    QFileInfo fi(filePath);
    info.sizeBytes = fi.size();

    QFile f(filePath);
    if (!f.open(QIODevice::ReadOnly)) return info;

    // 先读前 512KB
    QByteArray headData = f.read(512 * 1024);

    // 检查是否 AVI (RIFF 格式)
    if (headData.size() >= 12 && tagStr(headData, 0) == "RIFF" && tagStr(headData, 8) == "AVI ") {
        int w = 0, h = 0;
        qint64 durUs = 0;
        probeAVI(headData, w, h, durUs);
        info.width = w;
        info.height = h;
        info.rotation = 0;
        info.codec = "avc";
        info.durationUs = durUs;
        if (info.durationUs > 0 && info.sizeBytes > 0)
            info.bitrate = (int)(info.sizeBytes * 8 * 1000000LL / info.durationUs);
        info.hasAudio = true;
        info.frameRate = 30.0f;
        f.close();
        return info;
    }

    // MP4: 先在头部找 moov
    int moovOff = findMoov(headData);
    QByteArray moovData;

    if (moovOff >= 0) {
        // moov 在头部数据中
        quint32 moovSize = readBE32(headData, moovOff);
        moovData = headData.mid(moovOff, qMin((int)moovSize, headData.size() - moovOff));
    } else {
        // moov 不在头部，读文件尾部 2MB 搜索
        qint64 fileSize = fi.size();
        qint64 tailSize = qMin((qint64)2 * 1024 * 1024, fileSize);
        f.seek(fileSize - tailSize);
        QByteArray tailData = f.read(tailSize);
        moovOff = findMoov(tailData);
        if (moovOff >= 0) {
            quint32 moovSize = readBE32(tailData, moovOff);
            moovData = tailData.mid(moovOff, qMin((int)moovSize, tailData.size() - moovOff));
        }
    }
    f.close();

    if (!moovData.isEmpty() && moovData.size() >= 8) {
        int rot = 0, w = 0, h = 0;
        qint64 dur = 0; int ts = 0;
        QString codec;
        bool gotTkhd = false;
        // moovData[0..3] 是 size, [4..7] 是 "moov", [8..] 是内容
        scanBoxes(moovData, 8, moovData.size(),
                  rot, w, h, dur, ts, codec, gotTkhd);
        info.width = w;
        info.height = h;
        info.rotation = rot;
        info.codec = codec;
        if (ts > 0 && dur > 0)
            info.durationUs = dur * 1000000LL / ts;
    }

    if (info.durationUs > 0 && info.sizeBytes > 0)
        info.bitrate = (int)(info.sizeBytes * 8 * 1000000LL / info.durationUs);
    info.hasAudio = true;
    info.frameRate = 30.0f;
    if (info.codec.isEmpty()) info.codec = "avc";
    return info;
}
