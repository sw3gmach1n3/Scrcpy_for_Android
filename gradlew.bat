@echo off
setlocal
if not defined JAVA_HOME goto findJava
set "SCRCPY_JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%SCRCPY_JAVA_EXE%" goto runGradle
echo JAVA_HOME nu contine bin\java.exe: %JAVA_HOME%
exit /b 1
:findJava
set "SCRCPY_JAVA_EXE=java.exe"
:runGradle
"%SCRCPY_JAVA_EXE%" -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
