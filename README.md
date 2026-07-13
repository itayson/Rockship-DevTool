# RockFlash DevTool

Aplicativo Android para diagnosticar e administrar dispositivos Rockchip em Loader/MaskROM diretamente pelo USB OTG do celular.

O repositório mantém o nome `Rockship-DevTool`, mas o produto usa o nome **RockFlash DevTool**.

## Estado atual — 0.1.0-alpha01

Funcionalidades disponíveis:

- detecção de dispositivos com VID Rockchip `0x2207`;
- identificação inicial de `2207:320b` como Loader RK322x;
- permissão USB pelo Android;
- inspeção de interfaces e endpoints;
- abertura segura do file descriptor no backend JNI;
- backend opcional com root para executar um `rkdeveloptool` já instalado no Termux;
- comandos permitidos: `ld`, `rci`, `rid`, `rfi` e `rcb`;
- relatório JSON copiável;
- verificação de bateria, carregamento e economia de energia;
- CI para testes, lint e APK de depuração.

## Bloqueios de segurança

Esta versão não oferece:

- `ef` / apagar flash;
- `wl` / gravar LBA;
- `db` / baixar bootloader;
- restauração;
- flash automático de firmware.

Essas operações só serão adicionadas depois de existir backup, verificação de leitura, confirmação dupla, retomada e teste em hardware descartável.

## Requisitos para o backend root temporário

- Android com root;
- Termux com `rkdeveloptool` instalado em:
  `/data/data/com.termux/files/usr/bin/rkdeveloptool`;
- bibliotecas em `/data/data/com.termux/files/usr/lib`;
- TV Box em Loader/MaskROM;
- USB OTG e alimentação estável.

O objetivo final é substituir esse backend temporário por um núcleo nativo sem Termux, usando Android USB Host + JNI/libusb.

## Build

Requisitos:

- JDK 17;
- Android SDK 35;
- NDK `27.0.12077973`;
- CMake `3.22.1`;
- Gradle 8.9.

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Arquitetura e planejamento

- [Arquitetura](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Segurança](SECURITY.md)
- [Política de IA](docs/AI_POLICY.md)

## Licença

O repositório utiliza AGPL-3.0. Componentes de terceiros mantêm suas licenças originais.
