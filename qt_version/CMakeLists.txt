# AI生成
cmake_minimum_required(VERSION 3.16)
project(ShrinkCamQt VERSION 1.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

# Qt5 查找
find_package(Qt5 COMPONENTS Widgets Multimedia MultimediaWidgets REQUIRED)

set(SOURCES
    main.cpp
    mainwindow.cpp
    videoprobe.cpp
    compressor.cpp
)

set(HEADERS
    mainwindow.h
    videoprobe.h
    compressplan.h
    compressor.h
)

add_executable(${PROJECT_NAME} ${SOURCES} ${HEADERS})

target_link_libraries(${PROJECT_NAME} PRIVATE
    Qt5::Widgets
    Qt5::Multimedia
    Qt5::MultimediaWidgets
)

if(WIN32)
    target_compile_definitions(${PROJECT_NAME} PRIVATE UNICODE _UNICODE)
endif()
