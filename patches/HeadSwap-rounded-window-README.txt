Скругление окна приложения CarBridge — правка хоста HeadSwap

Основа: HeadSwap Desktop v0.5.0, commit 080bb92364ae9a31f6740562f3d55688fc351e5d.
Файл: android/app/src/main/java/com/headswap/desk/desktop/AndroidDesktopActivity.kt.

Патч включает clipToOutline с радиусом 18 dp для контейнера TextureView окна приложения
при профиле MODE_ID=carbridge. При выходе из профиля скругление выключается.
Размеры дисплея, масштаб изображения и координаты касаний не меняются.

Применение из корня исходников HeadSwap:
  git apply --ignore-space-change --check HeadSwap-0.5.0-CarBridge-rounded-window.patch
  git apply --ignore-space-change HeadSwap-0.5.0-CarBridge-rounded-window.patch

Проверено: патч применяется к указанному исходному тегу, результат применения совпадает
с подготовленным изменением. Сборка изменённого HeadSwap и проверка на устройстве НЕ выполнялись.

Эта правка НЕ входит в CarBridge-0.52-HeadSwap-profile-v2-debug.apk.
Для её работы нужен выпуск HeadSwap с включённым патчем. Обновление существующего
HeadSwap должно быть подписано его исходным ключом; ключа автора здесь нет.
Для версии 0.5.0-hud.1 из журнала применимость к исходникам этого варианта нужно проверить отдельно.
