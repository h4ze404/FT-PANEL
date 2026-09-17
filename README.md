СОДЕРЖИМОЕ:  

  START.bat        — запуск веб-панели (откроется http://localhost:8123)  
  
  MinecraftManager\ — веб-панель (index.html + server.js), нужен Node.js  
  
  mods\            — моды для Minecraft 1.21.11 (Fabric):  
  
  afbaritone-1.0.0.jar              — авторыбалка, анти-аффк, автоконнект, реконнект  
      
  baritone-standalone-fabric-1.17.0.jar — Baritone (ходьба по координатам)  
      
  fabric-api-0.141.6-1.21.11.jar    — Fabric API (нужен для модов)  
      

КАК ЗАПУСКАТЬ:
  1. Скопируй ВСЕ три файла из папки mods в папку модов игры:
     %APPDATA%\.tlauncher\legacy\Minecraft\game\mods\
     (если копируешь повторно — старые версии удали)
  2. Запусти START.bat — откроется веб-панель в браузере
  3. Запусти Minecraft (TLauncher, версия 1.21.11 с Fabric)
  4. Бот появится в панели автоматически

ТРЕБОВАНИЯ:
  - Node.js (https://nodejs.org, любая свежая LTS-версия)
  - Minecraft 1.21.11 + Fabric Loader (TLauncher)
  - Java идёт вместе с TLauncher

БЕЗ ИНТЕРНЕТА:
  - Панель работает локально (localhost)
  - Иконки предметов в панели подгружаются из сети
    (без сети панель работает, просто без картинок)
