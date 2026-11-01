// AI生成
#include "mainwindow.h"
#include "videoprobe.h"
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFileDialog>
#include <QMediaContent>
#include <QFileInfo>
#include <QDateTime>
#include <QMessageBox>
#include <QFile>
#include <QDir>
#include <QApplication>
#include <QStandardPaths>
#include <QFrame>
#include <QFont>
#include <QSizePolicy>

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
{
    setupUI();
    applyStyle();
    setWindowTitle("ShrinkCam - 视频压缩");
    setFixedSize(420, 720);
    m_mediaPlayer = new QMediaPlayer(this);
    m_mediaPlayer->setVideoOutput(m_videoWidget);
}

void MainWindow::setupUI()
{
    m_stack = new QStackedWidget;
    setCentralWidget(m_stack);

    // ===== 首页 =====
    m_homePage = new QWidget;
    auto *homeLay = new QVBoxLayout(m_homePage);
    homeLay->setSpacing(12);
    homeLay->setContentsMargins(20, 20, 20, 20);

    // 标题
    auto *titleRow = new QHBoxLayout;
    auto *logoLbl = new QLabel("🎬");
    logoLbl->setFixedSize(38, 38);
    logoLbl->setAlignment(Qt::AlignCenter);
    logoLbl->setObjectName("logo");
    titleRow->addWidget(logoLbl);
    auto *titleCol = new QVBoxLayout;
    auto *titleLbl = new QLabel("压缩相机");
    titleLbl->setObjectName("title");
    titleCol->addWidget(titleLbl);
    auto *engineLbl = new QLabel("FFmpeg 引擎就绪");
    engineLbl->setObjectName("subtitle");
    titleCol->addWidget(engineLbl);
    titleRow->addLayout(titleCol);
    titleRow->addStretch();
    homeLay->addLayout(titleRow);

    // 选择文件
    auto *fileRow = new QHBoxLayout;
    m_filePathEdit = new QLineEdit;
    m_filePathEdit->setPlaceholderText("选择视频文件...");
    m_filePathEdit->setReadOnly(true);
    fileRow->addWidget(m_filePathEdit);
    auto *browseBtn = new QPushButton("浏览");
    browseBtn->setObjectName("secondaryBtn");
    connect(browseBtn, &QPushButton::clicked, this, &MainWindow::onSelectFile);
    fileRow->addWidget(browseBtn);
    homeLay->addLayout(fileRow);

    // 视频信息
    m_videoInfoLabel = new QLabel;
    m_videoInfoLabel->setObjectName("infoCard");
    m_videoInfoLabel->setWordWrap(true);
    m_videoInfoLabel->hide();
    homeLay->addWidget(m_videoInfoLabel);

    m_estimateLabel = new QLabel;
    m_estimateLabel->setObjectName("estimateCard");
    m_estimateLabel->setWordWrap(true);
    m_estimateLabel->hide();
    homeLay->addWidget(m_estimateLabel);

    // 压缩强度
    auto *qualityLbl = new QLabel("压缩强度");
    qualityLbl->setObjectName("sectionLabel");
    homeLay->addWidget(qualityLbl);

    m_qualityExtreme = new QRadioButton("极限压缩 · 720p · 体积最小");
    m_qualityBalanced = new QRadioButton("均衡 · 1080p · 体积与画质折中");
    m_qualityClear = new QRadioButton("清晰 · 1080p · 画质优先");
    m_qualityExtreme->setChecked(true);
    connect(m_qualityExtreme, &QRadioButton::toggled, this, &MainWindow::onQualityChanged);
    connect(m_qualityBalanced, &QRadioButton::toggled, this, &MainWindow::onQualityChanged);
    connect(m_qualityClear, &QRadioButton::toggled, this, &MainWindow::onQualityChanged);
    homeLay->addWidget(m_qualityExtreme);
    homeLay->addWidget(m_qualityBalanced);
    homeLay->addWidget(m_qualityClear);

    // 目标体积
    auto *targetFrame = new QFrame;
    targetFrame->setObjectName("card");
    auto *targetLay = new QVBoxLayout(targetFrame);
    targetLay->setContentsMargins(14, 14, 14, 14);
    auto *targetHeader = new QHBoxLayout;
    auto *targetTitle = new QLabel("目标体积");
    targetTitle->setObjectName("cardTitle");
    targetHeader->addWidget(targetTitle);
    targetHeader->addStretch();
    m_targetLabel = new QLabel("不限（码率优先）");
    m_targetLabel->setObjectName("accentText");
    targetHeader->addWidget(m_targetLabel);
    targetLay->addLayout(targetHeader);
    m_targetSlider = new QSlider(Qt::Horizontal);
    m_targetSlider->setRange(0, 50);
    m_targetSlider->setValue(0);
    connect(m_targetSlider, &QSlider::valueChanged, this, &MainWindow::onTargetChanged);
    targetLay->addWidget(m_targetSlider);
    auto *quickRow = new QHBoxLayout;
    quickRow->setSpacing(6);
    for (int mb : {0, 3, 8, 20, 50}) {
        auto *btn = new QPushButton(mb == 0 ? "不限" : QString("%1M").arg(mb));
        btn->setObjectName("quickBtn");
        btn->setProperty("mb", mb);
        connect(btn, &QPushButton::clicked, this, [this, mb]() {
            m_targetSlider->setValue(mb);
        });
        quickRow->addWidget(btn);
    }
    targetLay->addLayout(quickRow);
    homeLay->addWidget(targetFrame);

    // 错误提示
    m_errorLabel = new QLabel;
    m_errorLabel->setObjectName("errorText");
    m_errorLabel->setWordWrap(true);
    m_errorLabel->hide();
    homeLay->addWidget(m_errorLabel);

    // 开始按钮
    m_startBtn = new QPushButton("先选择一个视频");
    m_startBtn->setObjectName("primaryBtn");
    m_startBtn->setEnabled(false);
    connect(m_startBtn, &QPushButton::clicked, this, &MainWindow::onStartCompress);
    homeLay->addWidget(m_startBtn);
    homeLay->addStretch();

    m_stack->addWidget(m_homePage);

    // ===== 进度页 =====
    m_progressPage = new QWidget;
    auto *progLay = new QVBoxLayout(m_progressPage);
    progLay->setSpacing(16);
    progLay->setContentsMargins(28, 28, 28, 28);
    progLay->addStretch();
    m_progressPercent = new QLabel("0%");
    m_progressPercent->setObjectName("bigPercent");
    m_progressPercent->setAlignment(Qt::AlignCenter);
    progLay->addWidget(m_progressPercent);
    m_progressBar = new QProgressBar;
    m_progressBar->setRange(0, 100);
    m_progressBar->setTextVisible(false);
    m_progressBar->setFixedHeight(10);
    progLay->addWidget(m_progressBar);
    m_progressText = new QLabel("准备中...");
    m_progressText->setObjectName("progressMsg");
    m_progressText->setAlignment(Qt::AlignCenter);
    progLay->addWidget(m_progressText);
    m_passText = new QLabel;
    m_passText->setObjectName("accentText");
    m_passText->setAlignment(Qt::AlignCenter);
    progLay->addWidget(m_passText);
    m_progressDetail = new QLabel;
    m_progressDetail->setObjectName("subtitle");
    m_progressDetail->setAlignment(Qt::AlignCenter);
    progLay->addWidget(m_progressDetail);
    progLay->addStretch();
    auto *cancelBtn = new QPushButton("取消");
    cancelBtn->setObjectName("cancelBtn");
    cancelBtn->setFixedWidth(160);
    connect(cancelBtn, &QPushButton::clicked, this, &MainWindow::onCancelCompress);
    progLay->addWidget(cancelBtn, 0, Qt::AlignCenter);
    progLay->addStretch();
    m_stack->addWidget(m_progressPage);

    // ===== 结果页 =====
    m_resultPage = new QWidget;
    auto *resLay = new QVBoxLayout(m_resultPage);
    resLay->setSpacing(14);
    resLay->setContentsMargins(20, 20, 20, 20);
    auto *backRow = new QHBoxLayout;
    auto *backBtn = new QPushButton("← 返回");
    backBtn->setObjectName("textBtn");
    connect(backBtn, &QPushButton::clicked, this, &MainWindow::onBackHome);
    backRow->addWidget(backBtn);
    auto *resTitle = new QLabel("压缩完成");
    resTitle->setObjectName("title");
    backRow->addWidget(resTitle);
    backRow->addStretch();
    resLay->addLayout(backRow);
    auto *ratioFrame = new QFrame;
    ratioFrame->setObjectName("ratioCard");
    auto *ratioLay = new QVBoxLayout(ratioFrame);
    ratioLay->setContentsMargins(20, 20, 20, 20);
    ratioLay->setSpacing(6);
    auto *ratioCaption = new QLabel("体积减少");
    ratioCaption->setObjectName("subtitle");
    ratioCaption->setAlignment(Qt::AlignCenter);
    ratioLay->addWidget(ratioCaption);
    m_ratioLabel = new QLabel("0%");
    m_ratioLabel->setObjectName("hugePercent");
    m_ratioLabel->setAlignment(Qt::AlignCenter);
    ratioLay->addWidget(m_ratioLabel);
    auto *sizeRow = new QHBoxLayout;
    sizeRow->addStretch();
    m_sizeBeforeLabel = new QLabel;
    m_sizeBeforeLabel->setObjectName("subtitle");
    sizeRow->addWidget(m_sizeBeforeLabel);
    auto *arrowLbl = new QLabel(" → ");
    arrowLbl->setObjectName("subtitle");
    sizeRow->addWidget(arrowLbl);
    m_sizeAfterLabel = new QLabel;
    m_sizeAfterLabel->setObjectName("accentText");
    sizeRow->addWidget(m_sizeAfterLabel);
    sizeRow->addStretch();
    ratioLay->addLayout(sizeRow);
    m_detailLabel = new QLabel;
    m_detailLabel->setObjectName("subtitle");
    m_detailLabel->setAlignment(Qt::AlignCenter);
    ratioLay->addWidget(m_detailLabel);
    resLay->addWidget(ratioFrame);
    m_videoWidget = new QVideoWidget;
    m_videoWidget->setFixedHeight(220);
    resLay->addWidget(m_videoWidget);
    auto *actionRow = new QHBoxLayout;
    actionRow->setSpacing(12);
    auto *shareBtn = new QPushButton("分享");
    shareBtn->setObjectName("primaryBtn");
    connect(shareBtn, &QPushButton::clicked, this, &MainWindow::onShareResult);
    actionRow->addWidget(shareBtn);
    auto *playBtn = new QPushButton("播放");
    playBtn->setObjectName("secondaryBtn");
    connect(playBtn, &QPushButton::clicked, this, &MainWindow::onPlayResult);
    actionRow->addWidget(playBtn);
    resLay->addLayout(actionRow);
    resLay->addStretch();
    m_stack->addWidget(m_resultPage);
}

void MainWindow::applyStyle()
{
    setStyleSheet(R"(
        * { font-family: "Microsoft YaHei", "Segoe UI", sans-serif; }
        QMainWindow { background: #0F1F1B; }

        QLabel#title { font-size: 20px; font-weight: bold; color: #E8F5F0; }
        QLabel#subtitle { font-size: 12px; color: #7A9B92; }
        QLabel#sectionLabel { font-size: 13px; font-weight: 500; color: #7A9B92; }
        QLabel#accentText { font-size: 13px; font-weight: bold; color: #00D9A3; }
        QLabel#cardTitle { font-size: 14px; font-weight: 600; color: #E8F5F0; }

        QLabel#logo {
            background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #00D9A3, stop:1 #00A87E);
            border-radius: 11px; font-size: 20px;
        }

        QLabel#infoCard, QLabel#estimateCard {
            background: #1A2D27; border-radius: 14px; padding: 12px;
            font-size: 12px; color: #B0CCC4;
        }
        QLabel#estimateCard { color: #00D9A3; font-weight: 500; }
        QLabel#errorText { color: #FF6B6B; font-size: 13px; }

        QLineEdit {
            background: #1A2D27; border: 1px solid #2A4A3E; border-radius: 10px;
            padding: 10px 14px; color: #E8F5F0; font-size: 13px;
        }

        QRadioButton {
            color: #E8F5F0; font-size: 13px; padding: 8px;
            background: #1A2D27; border-radius: 12px; margin: 2px;
        }
        QRadioButton::indicator { width: 16px; height: 16px; }
        QRadioButton::indicator:unchecked {
            border: 2px solid #2A4A3E; border-radius: 9px; background: #1A2D27;
        }
        QRadioButton::indicator:checked {
            border: 2px solid #00D9A3; border-radius: 9px; background: #00D9A3;
        }
        QRadioButton:checked { background: #213D35; border: 1px solid #00D9A3; }

        QFrame#card { background: #1A2D27; border-radius: 14px; }
        QFrame#ratioCard {
            background: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #17372F, stop:1 #132A28);
            border-radius: 20px;
        }

        QSlider::groove:horizontal {
            height: 6px; background: #2A4A3E; border-radius: 3px;
        }
        QSlider::handle:horizontal {
            width: 18px; height: 18px; margin: -6px 0; border-radius: 9px;
            background: #00D9A3;
        }
        QSlider::sub-page:horizontal { background: #00D9A3; border-radius: 3px; }

        QPushButton#primaryBtn {
            background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #00D9A3, stop:1 #00A87E);
            color: #0F1F1B; font-size: 16px; font-weight: bold; border: none;
            border-radius: 14px; padding: 14px;
        }
        QPushButton#primaryBtn:disabled {
            background: #1A2D27; color: #7A9B92;
        }
        QPushButton#secondaryBtn {
            background: #1A2D27; color: #00D9A3; font-size: 14px; font-weight: bold;
            border: 1px solid #2A4A3E; border-radius: 14px; padding: 12px;
        }
        QPushButton#quickBtn {
            background: #2A4A3E; color: #7A9B92; font-size: 11px; border: none;
            border-radius: 8px; padding: 5px 10px;
        }
        QPushButton#quickBtn:hover { background: #00D9A3; color: #0F1F1B; }
        QPushButton#cancelBtn {
            background: #1A2D27; color: #FF6B6B; font-size: 14px; font-weight: 600;
            border: none; border-radius: 14px; padding: 12px;
        }
        QPushButton#textBtn {
            background: transparent; color: #00D9A3; font-size: 13px; border: none;
        }

        QProgressBar {
            background: #1A2D27; border: none; border-radius: 5px;
        }
        QProgressBar::chunk {
            background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #00D9A3, stop:1 #00A87E);
            border-radius: 5px;
        }

        QLabel#bigPercent { font-size: 34px; font-weight: bold; color: #E8F5F0; }
        QLabel#hugePercent { font-size: 46px; font-weight: 800; color: #00D9A3; }
        QLabel#progressMsg { font-size: 14px; color: #E8F5F0; }

        QVideoWidget { background: black; border-radius: 16px; }
    )");
}

void MainWindow::switchPage(int index)
{
    m_stack->setCurrentIndex(index);
}

void MainWindow::onSelectFile()
{
    QString path = QFileDialog::getOpenFileName(this, "选择视频", QString(),
        "视频文件 (*.mp4 *.avi *.mkv *.mov *.flv *.wmv *.3gp);;所有文件 (*)");
    if (path.isEmpty()) return;

    m_inputPath = path;
    m_filePathEdit->setText(QFileInfo(path).fileName());
    m_errorLabel->hide();

    m_videoInfo = VideoProbe::probe(path);
    if (m_videoInfo.width <= 0 || m_videoInfo.height <= 0) {
        m_errorLabel->setText("无法读取该视频，请换个文件试试");
        m_errorLabel->show();
        m_videoInfoLabel->hide();
        m_estimateLabel->hide();
        m_startBtn->setEnabled(false);
        m_startBtn->setText("先选择一个视频");
        return;
    }

    updateHomeInfo();
    m_startBtn->setEnabled(true);
    m_startBtn->setText("开始压缩");
}

void MainWindow::onQualityChanged()
{
    if (m_qualityExtreme->isChecked()) m_quality = Quality::Extreme;
    else if (m_qualityBalanced->isChecked()) m_quality = Quality::Balanced;
    else m_quality = Quality::Clear;
    updateHomeInfo();
}

void MainWindow::onTargetChanged(int val)
{
    m_targetBytes = (val > 0) ? (qint64)val * 1024 * 1024 : 0;
    m_targetLabel->setText(val > 0 ? QString("≈ %1 MB").arg(val) : "不限（码率优先）");
    updateHomeInfo();
}

void MainWindow::updateHomeInfo()
{
    if (m_inputPath.isEmpty() || m_videoInfo.width <= 0) return;
    auto &info = m_videoInfo;
    m_videoInfoLabel->setText(
        QString("%1 × %2 · %3 · %4%5 · %6")
            .arg(info.displayW()).arg(info.displayH())
            .arg(Fmt::duration(info.durationUs))
            .arg(info.codec)
            .arg(info.rotation > 0 ? QString(" (旋转%1°)").arg(info.rotation) : "")
            .arg(Fmt::size(info.sizeBytes)));
    m_videoInfoLabel->show();

    auto plan = CompressPlan::make(info, m_quality, m_targetBytes);
    qint64 src = info.sizeBytes;
    qint64 est = plan.estimateBytes;
    m_estimateLabel->setText(
        QString("预计 %1 · 省 %2 · %3 · %4kbps")
            .arg(Fmt::size(est))
            .arg(Fmt::percent(1.0 - (double)est / qMax<qint64>(1, src)))
            .arg(plan.codecName())
            .arg(plan.videoBitrate / 1000));
    m_estimateLabel->show();
}

void MainWindow::generateOutputPath()
{
    QString dir = QStandardPaths::writableLocation(QStandardPaths::MoviesLocation);
    if (dir.isEmpty()) dir = QDir::currentPath();
    QString stamp = QDateTime::currentDateTime().toString("yyyyMMdd_HHmmss");
    char q = m_quality == Quality::Extreme ? 'x' : (m_quality == Quality::Balanced ? 'b' : 'c');
    m_outputPath = QDir(dir).filePath(QString("SHRINK_%1_%2.mp4").arg(stamp).arg(q));
}

void MainWindow::onStartCompress()
{
    if (m_inputPath.isEmpty()) return;
    generateOutputPath();

    m_progressPercent->setText("0%");
    m_progressBar->setValue(0);
    m_progressText->setText("准备中...");
    m_passText->clear();
    m_progressDetail->setText(
        QString("%1 · %2×%3 · %4")
            .arg(qualityInfo(m_quality).label)
            .arg(m_videoInfo.displayW()).arg(m_videoInfo.displayH())
            .arg(Fmt::duration(m_videoInfo.durationUs)));
    switchPage(1);

    m_compressor = new Compressor(this);
    m_compressor->setParams(m_inputPath, m_videoInfo, m_quality, m_targetBytes, m_outputPath);
    connect(m_compressor, &Compressor::progress, this, &MainWindow::onCompressProgress);
    connect(m_compressor, &Compressor::finished, this, &MainWindow::onCompressFinished);
    m_compressor->start();
}

void MainWindow::onCancelCompress()
{
    if (m_compressor) m_compressor->cancel();
}

void MainWindow::onCompressProgress(int percent, const QString &msg, const QString &passText)
{
    m_progressPercent->setText(QString("%1%").arg(percent));
    m_progressBar->setValue(percent);
    m_progressText->setText(msg);
    if (!passText.isEmpty()) m_passText->setText(passText);
}

void MainWindow::onCompressFinished()
{
    if (!m_compressor) return;
    bool ok = m_compressor->success();
    QString err = m_compressor->error();

    if (!ok) {
        m_compressor->deleteLater();
        m_compressor = nullptr;
        switchPage(0);
        m_errorLabel->setText(err.isEmpty() ? "压缩失败" : err);
        m_errorLabel->show();
        return;
    }

    qint64 srcSize = m_videoInfo.sizeBytes;
    qint64 outSize = m_compressor->outputSize();
    auto plan = m_compressor->plan();
    int passes = m_compressor->passes();
    qint64 elapsed = m_compressor->elapsedMs();

    m_ratioLabel->setText(Fmt::percent(1.0 - (double)outSize / qMax<qint64>(1, srcSize)));
    m_sizeBeforeLabel->setText(Fmt::size(srcSize));
    m_sizeAfterLabel->setText(Fmt::size(outSize));
    m_detailLabel->setText(
        QString("压缩比 %1 · 省下 %2 · 耗时 %3s · %4轮")
            .arg(Fmt::ratio(srcSize, outSize))
            .arg(Fmt::size(srcSize - outSize))
            .arg(elapsed / 1000.0, 0, 'f', 1)
            .arg(passes));

    m_compressor->deleteLater();
    m_compressor = nullptr;
    switchPage(2);
}

void MainWindow::onBackHome()
{
    if (m_mediaPlayer) m_mediaPlayer->stop();
    switchPage(0);
}

void MainWindow::onPlayResult()
{
    if (m_outputPath.isEmpty() || !QFile::exists(m_outputPath)) return;
    m_mediaPlayer->setMedia(QMediaContent(QUrl::fromLocalFile(m_outputPath)));
    m_mediaPlayer->play();
}

void MainWindow::onShareResult()
{
    if (m_outputPath.isEmpty() || !QFile::exists(m_outputPath)) return;
    QString dir = QFileInfo(m_outputPath).absolutePath();
    QMessageBox::information(this, "已保存",
        QString("压缩后的视频已保存到:\n%1").arg(m_outputPath));
}
