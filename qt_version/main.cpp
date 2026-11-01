// AI生成
#include "mainwindow.h"
#include <QApplication>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setApplicationName("ShrinkCam");
    app.setApplicationVersion("1.1.0");

    MainWindow w;
    w.show();

    return app.exec();
}
