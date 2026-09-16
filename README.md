КАК ЗАПУСТИТЬ НА НОВОМ КОМПЬЮТЕРЕ  
---------------------------------  
1. Установить TLauncher и запустить Minecraft 1.21.11 (Fabric).  
2. Скопировать ВСЕ 3 файла из папки mods\ в:  
   C:\Users\<имя>\AppData\Roaming\.tlauncher\legacy\Minecraft\game\mods\  
   - afbaritone-1.0.0.jar               (сам мод)  
   - fabric-api-0.141.6-1.21.11.jar     (Fabric API — обязателен)  
   - baritone-standalone-fabric-1.17.0.jar (Baritone — для ходьбы)  
3. Установить Node.js (https://nodejs.org, версия LTS или новее).  
4. Запустить MinecraftManager\START.bat — откроется браузер  
   с панелью на http://localhost:8123  

ВАЖНО  
-----  
- Панель запускает игру через TLauncher. При первом запуске  
  запусти игру вручную один раз, чтобы в логах лаунчера  
  появилась команда старта (панель её подхватит как шаблон).  
- Панель ищет пути через %APPDATA%\.tlauncher — на стандартной  
  установке TLauncher всё совпадает автоматически.  
- Parity: мод сам подключается к mc.funtime.su при старте игры.  

ФАЙЛЫ ПАНЕЛИ (сохраняются рядом с server.js)  
--------------------------------------------  
nicknames.json — сохранённые ники  
coords.json    — сохранённые координаты  
events.json    — лог событий  
