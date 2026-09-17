@echo off
title AFB-Run: Minecraft Manager
cd /d "%~dp0MinecraftManager"
start "" http://localhost:8123
node server.js
pause
