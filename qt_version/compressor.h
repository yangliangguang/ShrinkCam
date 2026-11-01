// AI生成
#ifndef COMPRESSOR_H
#define COMPRESSOR_H

#include "compressplan.h"
#include <QThread>
#include <QString>
#include <atomic>

// FFmpeg 外部进程压缩器（QThread 工作线程）
class Compressor : public QThread
{
    Q_OBJECT
public:
    explicit Compressor(QObject *parent = nullptr);

    // 设置压缩参数
    void setParams(const QString &inputPath,
                   const VideoInfo &info,
                   Quality quality,
                   qint64 targetBytes,
                   const QString &outputPath);

    void cancel() { m_cancel = true; }

    // 结果
    QString outputPath() const { return m_outputPath; }
    qint64 outputSize() const;
    int passes() const { return m_passes; }
    qint64 elapsedMs() const { return m_elapsedMs; }
    CompressPlan plan() const { return m_plan; }
    QString error() const { return m_error; }
    bool success() const { return m_success; }

    // 查找 ffmpeg 可执行路径
    static QString findFfmpeg();

signals:
    void progress(int percent, const QString &message, const QString &passText);

protected:
    void run() override;

private:
    bool runPass(const QString &input, const CompressPlan &plan,
                 const QString &output, int passNo);

    QString m_inputPath;
    VideoInfo m_info;
    Quality m_quality = Quality::Extreme;
    qint64 m_targetBytes = 0;
    QString m_outputPath;
    CompressPlan m_plan;
    std::atomic<bool> m_cancel{false};
    int m_passes = 1;
    qint64 m_elapsedMs = 0;
    bool m_success = false;
    QString m_error;
};

#endif // COMPRESSOR_H
