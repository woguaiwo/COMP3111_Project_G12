@echo off
setlocal

set "SCENE_BUILDER=%LOCALAPPDATA%\SceneBuilder\SceneBuilder.exe"
if not exist "%SCENE_BUILDER%" set "SCENE_BUILDER=%ProgramFiles%\SceneBuilder\SceneBuilder.exe"
if not exist "%SCENE_BUILDER%" set "SCENE_BUILDER=%ProgramFiles(x86)%\SceneBuilder\SceneBuilder.exe"

if not exist "%SCENE_BUILDER%" (
    echo Scene Builder was not found.
    echo Install it with: winget install --id Gluon.SceneBuilder --exact
    exit /b 1
)

set "FXML_FILE=%~1"
if "%FXML_FILE%"=="" set "FXML_FILE=%~dp0src\main\resources\fxml\WelcomeView.fxml"
if not exist "%FXML_FILE%" (
    echo FXML file not found: "%FXML_FILE%"
    exit /b 1
)

start "" "%SCENE_BUILDER%" "%FXML_FILE%"
