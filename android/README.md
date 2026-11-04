# MK64 Família (projeto Android)

Casca Android do SpaghettiKart. O jogo em si (C/C++) é compilado pelo CMake com o
toolchain do Android NDK e vira `libmain.so`; o Gradle só empacota isso num APK.

O APK NÃO contém a ROM nem os assets do jogo.

## Baixar o APK

Aba **Actions** do GitHub → workflow **Android APK** → execução mais recente →
artefato `MK64Familia-android-debug` (dentro vem o `app-debug.apk`).

## Arquivos que precisam ser copiados para o celular

Abra o app uma vez (ele cria a pasta) e depois copie, pelo cabo USB, para:

    Armazenamento interno/Android/data/dev.mdhigino.mk64family/files/

- `mk64.o2r` (gerado no PC com a sua ROM americana)
- `spaghetti.o2r` (vem junto do release do SpaghettiKart para PC)
- `config.yml` e as pastas `yamls/` e `meta/` (também vêm no release para PC)

Se o jogo reclamar de algum arquivo ausente, o log mostra qual (ver `ci-logs` / `adb logcat`).
