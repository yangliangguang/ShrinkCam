// AI生成
#include "compressor.h"
#include <QProcess>
#include <QFileInfo>
#include <QFile>
#include <QDir>
#include <QRegularExpression>
#include <QDateTime>

Compressor::Compressor(QObject *parent)
    : QThread(parent)
{
}

void Compressor::setParams(const QString &inputPath, const VideoInfo &info,
                           Quality quality, qint64 targetBytes, const QString &outputPath)
{
    m_inputPath = inputPath;
    m_info = info;
    m_quality = quality;
    m_targetBytes = targetBytes;
    m_outputPath = outputPath;
    m_cancel = false;
    m_passes = 1;
    m_success = false;
    m_error.clear();
}

qint64 Compressor::outputSize() const
{
    QFileInfo fi(m_outputPath);
    return fi.exists() ? fi.size() : 0;
}

QString Compressor::findFfmpeg()
{
    QStringList candidates = {
        "ffmpeg",
        "ffmpeg/bin/ffmpeg.exe",
        "C:/ffmpeg/bin/ffmpeg.exe",
        "C:/Program Files/ffmpeg/bin/ffmpeg.exe",
        "D:/ffmpeg/bin/ffmpeg.exe",
    };
    for (const auto &c : candidates) {
        QProcess p;
        p.start(c, {"-version"});
        if (p.waitForFinished(3000) && p.exitCode() == 0)
            return c;
    }
    return "ffmpeg";
}

bool Compressor::runPass(const QString &input, const CompressPlan &plan,
                         const QString &output, int passNo)
{
    QString ffmpeg = findFfmpeg();
    QStringList args;

    // 输入
    args << "-i" << input;

    // 视频滤镜：缩放 + 旋转
    QString vf = QString("scale=%1:%2").arg(plan.outWidth).arg(plan.outHeight);
    if (m_info.rotation == 90)  vf += ",transpose=1";
    else if (m_info.rotation == 180) vf += ",transpose=2";
    else if (m_info.rotation == 270) vf += ",transpose=3";

    // 视频编码
    QString vCodec = plan.useHevc ? "libx265" : "libx264";
    args << "-vf" << vf
         << "-c:v" << vCodec
         << "-b:v" << QString::number(plan.videoBitrate)
         << "-r" << QString::number(plan.outFps)
         << "-g" << QString::number(plan.outFps * 2); // GOP = 2s

    if (vCodec == "libx265") {
        args << "-x265-params" << "log-level=error";
    } else {
        args << "-preset" << "medium";
    }

    // 音频编码
    if (plan.audioBitrate > 0 && m_info.hasAudio) {
        int outRate = (plan.audioBitrate <= 40000) ? 24000 : qMin(m_info.audioSampleRate, 48000);
        int outCh   = (plan.audioBitrate <= 40000) ? 1 : qMin(m_info.audioChannels, 2);
        args << "-c:a" << "aac"
             << "-b:a" << QString::number(plan.audioBitrate)
             << "-ar" << QString::number(outRate)
             << "-ac" << QString::number(outCh);
    } else {
        args << "-an"; // 无音频
    }

    // 输出
    args << "-y" << output;

    QProcess proc;
    proc.start(ffmpeg, args);
    if (!proc.waitForStarted()) {
        m_error = QString("无法启动 ffmpeg: %1").arg(ffmpeg);
        return false;
    }

    // 解析进度（ffmpeg 输出到 stderr）
    qint64 totalUs = qMax<qint64>(1, m_info.durationUs);
    QRegularExpression re("time=(\\d+:\\d+:\\d+\\.\\d+)");

    while (proc.state() != QProcess::NotRunning) {
        if (m_cancel) {
            proc.kill();
            proc.waitForFinished(3000);
            return false;
        }
        if (proc.waitForReadyRead(200)) {
            QString out = QString::fromLocal8Bit(proc.readAllStandardError());
            auto match = re.match(out);
            if (match.hasMatch()) {
                QString t = match.captured(1);
                auto parts = t.split(':');
                double sec = parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble();
                qint64 us = (qint64)(sec * 1000000);
                int pct = (int)(us * 100 / totalUs);
                pct = qBound(0, pct, 99);
                QString passText = (passNo > 1)
                    ? QString("第 2 轮校准 · %1%").arg(pct)
                    : QString("%1%").arg(pct);
                emit progress(pct, "正在压缩", passText);
            }
        }
    }

    if (proc.exitCode() != 0) {
        if (!m_cancel) {
            m_error = QString("ffmpeg 退出码 %1: %2")
                .arg(proc.exitCode())
                .arg(QString::fromLocal8Bit(proc.readAllStandardError()).left(500));
        }
        return false;
    }
    return true;
}

void Compressor::run()
{
    QDateTime start = QDateTime::currentDateTime();

    m_plan = CompressPlan::make(m_info, m_quality, m_targetBytes);
    emit progress(0, "分析视频",
        QString("计划 %1×%2 · %3kbps · %4")
            .arg(m_plan.outWidth).arg(m_plan.outHeight)
            .arg(m_plan.videoBitrate / 1000).arg(m_plan.codecName()));

    QString tmp = m_outputPath + ".part.mp4";
    if (QFile::exists(tmp)) QFile::remove(tmp);

    if (!runPass(m_inputPath, m_plan, tmp, 1)) {
        if (m_cancel) return;
        m_success = false;
        return;
    }

    // 自适应校准
    if (m_targetBytes > 0 && !m_cancel) {
        QFileInfo fi(tmp);
        qint64 actual = fi.size();
        double diff = (double)(actual - m_targetBytes) / m_targetBytes;
        if (diff > 0.08 || diff < -0.20) {
            float factor = qBound(0.45, (double)m_targetBytes / actual, 1.6);
            CompressPlan plan2 = CompressPlan::make(m_info, m_quality, m_targetBytes);
            plan2.videoBitrate = qMax(60000, (int)(plan2.videoBitrate * factor));
            plan2.estimateBytes = m_targetBytes;
            if (plan2.videoBitrate < m_plan.videoBitrate || diff > 0.08) {
                m_passes = 2;
                emit progress(0, "体积校准中",
                    QString("第一轮 %1，正在校准到 %2")
                        .arg(Fmt::size(actual)).arg(Fmt::size(m_targetBytes)));
                m_plan = plan2;
                m_plan.outWidth = m_plan.outWidth;
                m_plan.outHeight = m_plan.outHeight;
                if (QFile::exists(tmp)) QFile::remove(tmp);
                if (!runPass(m_inputPath, m_plan, tmp, 2)) {
                    if (m_cancel) return;
                    m_success = false;
                    return;
                }
            }
        }
    }

    if (m_cancel) {
        QFile::remove(tmp);
        return;
    }

    // 重命名
    if (QFile::exists(m_outputPath)) QFile::remove(m_outputPath);
    QFile::rename(tmp, m_outputPath);

    m_elapsedMs = start.msecsTo(QDateTime::currentDateTime());
    m_success = true;
    emit progress(100, "完成", Fmt::size(outputSize()));
}
