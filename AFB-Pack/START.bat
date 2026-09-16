@echo off
title AFB-Pack: Minecraft Manager
cd /d "%~dp0MinecraftManager"
start "" http://localhost:8123
node server.js
pause
