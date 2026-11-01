// AI生成
#ifndef VIDEOPROBE_H
#define VIDEOPROBE_H

#include "compressplan.h"
#include <QString>

// 使用 ffprobe 外部进程探测视频元信息
class VideoProbe
{
public:
    // 探测视频文件，返回 VideoInfo；失败返回 width=0
    static VideoInfo probe(const QString &filePath);

    // 查找 ffprobe 可执行路径
    static QString findFfprobe();

private:
    static int parseRotation(const QString &filePath);
};

#endif // VIDEOPROBE_H
