// AI生成
#ifndef MAINWINDOW_H
#define MAINWINDOW_H

#include "compressplan.h"
#include "compressor.h"
#include <QMainWindow>
#include <QStackedWidget>
#include <QLabel>
#include <QPushButton>
#include <QProgressBar>
#include <QRadioButton>
#include <QSlider>
#include <QLineEdit>
#include <QVideoWidget>
#include <QMediaPlayer>

class MainWindow : public QMainWindow
{
    Q_OBJECT
public:
    explicit MainWindow(QWidget *parent = nullptr);

private slots:
    void onSelectFile();
    void onQualityChanged();
    void onTargetChanged(int val);
    void onStartCompress();
    void onCancelCompress();
    void onCompressProgress(int percent, const QString &msg, const QString &passText);
    void onCompressFinished();
    void onBackHome();
    void onPlayResult();
    void onShareResult();

private:
    void setupUI();
    void applyStyle();
    void updateHomeInfo();
    void switchPage(int index);
    void generateOutputPath();

    // 页面容器
    QStackedWidget *m_stack;
    QWidget *m_homePage;
    QWidget *m_progressPage;
    QWidget *m_resultPage;

    // 首页控件
    QLineEdit *m_filePathEdit;
    QLabel *m_videoInfoLabel;
    QLabel *m_estimateLabel;
    QRadioButton *m_qualityExtreme;
    QRadioButton *m_qualityBalanced;
    QRadioButton *m_qualityClear;
    QSlider *m_targetSlider;
    QLabel *m_targetLabel;
    QPushButton *m_startBtn;
    QLabel *m_errorLabel;

    // 进度页控件
    QProgressBar *m_progressBar;
    QLabel *m_progressPercent;
    QLabel *m_progressText;
    QLabel *m_passText;
    QLabel *m_progressDetail;

    // 结果页控件
    QLabel *m_ratioLabel;
    QLabel *m_sizeBeforeLabel;
    QLabel *m_sizeAfterLabel;
    QLabel *m_detailLabel;
    QVideoWidget *m_videoWidget;
    QMediaPlayer *m_mediaPlayer;

    // 状态
    QString m_inputPath;
    VideoInfo m_videoInfo;
    Quality m_quality = Quality::Extreme;
    qint64 m_targetBytes = 0;
    Compressor *m_compressor = nullptr;
    QString m_outputPath;
};

#endif // MAINWINDOW_H
